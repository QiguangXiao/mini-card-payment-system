package com.minicard.statement.application;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minicard.shared.domain.Money;
import com.minicard.statement.domain.Statement;
import com.minicard.statement.domain.StatementLineSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StatementReadServiceTest {

    private static final Duration REMOTE_TTL = Duration.ofMinutes(5);
    private static final Duration REMOTE_TTL_JITTER = Duration.ofSeconds(30);

    private StatementGenerationService statementGenerationService;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private ObjectMapper objectMapper;
    private StatementReadCacheBroadcaster broadcaster;
    private StatementReadService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        statementGenerationService = mock(StatementGenerationService.class);
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        objectMapper = new ObjectMapper().findAndRegisterModules();
        broadcaster = mock(StatementReadCacheBroadcaster.class);
        service = new StatementReadService(
                statementGenerationService,
                redisTemplate,
                objectMapper,
                new StatementReadCacheProperties(
                        Duration.ofSeconds(30),
                        1000L,
                        REMOTE_TTL,
                        REMOTE_TTL_JITTER,
                        Duration.ofSeconds(10)
                ),
                broadcaster
        );
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void loadsFromDbThenServesSameJvmReadsFromCaffeineL1() {
        Statement statement = statement();
        String redisKey = redisKey(statement.id());
        when(valueOperations.get(redisKey)).thenReturn(null);
        when(statementGenerationService.get(statement.id())).thenReturn(statement);

        StatementReadModel first = service.get(statement.id());
        StatementReadModel second = service.get(statement.id());

        assertThat(first).isSameAs(second);
        assertThat(first.id()).isEqualTo(statement.id());
        verify(statementGenerationService, times(1)).get(statement.id());
        // 写回 L2 走版本化 CAS（execute），信封 = "<version>|<json>"，TTL 在 [remoteTtl, remoteTtl+jitter]。
        ArgumentCaptor<Object> casArgs = ArgumentCaptor.forClass(Object.class);
        verify(redisTemplate).execute(any(RedisScript.class), eq(List.of(redisKey)),
                casArgs.capture(), casArgs.capture(), casArgs.capture());
        List<Object> args = casArgs.getAllValues();
        assertThat((String) args.get(0)).isEqualTo("0");                 // version = paidAmount minor units = 0
        assertThat((String) args.get(1)).startsWith("0|").contains("\"id\"");  // 真实值信封，非墓碑
        long ttlMillis = Long.parseLong((String) args.get(2));
        assertThat(ttlMillis).isBetween(REMOTE_TTL.toMillis(), REMOTE_TTL.plus(REMOTE_TTL_JITTER).toMillis());
    }

    @Test
    void loadsFromRedisL2WithoutCallingDb() throws Exception {
        Statement statement = statement();
        String redisKey = redisKey(statement.id());
        StatementReadModel model = StatementReadModel.from(statement);
        // L2 value 是 "<version>|<json>" 信封，不是裸 JSON。
        String envelope = model.version() + "|" + objectMapper.writeValueAsString(model);
        when(valueOperations.get(redisKey)).thenReturn(envelope);

        StatementReadModel readModel = service.get(statement.id());

        assertThat(readModel.id()).isEqualTo(statement.id());
        verify(statementGenerationService, never()).get(statement.id());
    }

    @Test
    void tombstoneInRedisIsTreatedAsMissAndReloadsFromDb() {
        // 墓碑信封 "<version>|"（'|' 后为空）应被当作 miss：读路径回源 DB 重建真值。
        Statement statement = statement();
        String redisKey = redisKey(statement.id());
        when(valueOperations.get(redisKey)).thenReturn("100|");
        when(statementGenerationService.get(statement.id())).thenReturn(statement);

        StatementReadModel readModel = service.get(statement.id());

        assertThat(readModel.id()).isEqualTo(statement.id());
        verify(statementGenerationService).get(statement.id());
    }

    @Test
    void evictInsideTransactionWritesTombstoneAndBroadcastsAfterCommit() {
        Statement statement = statement();   // 新关账 statement，paid=0 → version 0
        String key = redisKey(statement.id());
        TransactionSynchronizationManager.initSynchronization();

        service.evictAfterCommit(statement);

        // commit 前：不写 L2、不广播。
        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(), any(), any(), any());
        verify(broadcaster, never()).broadcastEvict(statement.id());

        TransactionSynchronizationManager.getSynchronizations()
                .forEach(synchronization -> synchronization.afterCommit());

        // afterCommit：写"版本地板"墓碑（CAS，信封 "0|" 末尾为空 = tombstone）+ 广播给其他 pod。
        ArgumentCaptor<Object> casArgs = ArgumentCaptor.forClass(Object.class);
        verify(redisTemplate).execute(any(RedisScript.class), eq(List.of(key)),
                casArgs.capture(), casArgs.capture(), casArgs.capture());
        assertThat((String) casArgs.getAllValues().get(1)).isEqualTo("0|");
        verify(broadcaster).broadcastEvict(statement.id());
    }

    @Test
    void invalidateLocalOnlyTouchesL1NotL2OrBroadcast() {
        // 订阅者收到其他 pod 的失效广播时只清本地 L1：绝不能再写 L2 或再广播，否则会形成广播风暴。
        UUID statementId = UUID.randomUUID();

        service.invalidateLocal(statementId);

        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(), any(), any(), any());
        verify(redisTemplate, never()).delete(anyString());
        verify(broadcaster, never()).broadcastEvict(statementId);
    }

    private Statement statement() {
        return Statement.close(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                LocalDate.parse("2026-06-01"),
                LocalDate.parse("2026-06-30"),
                LocalDate.parse("2026-07-25"),
                List.of(new StatementLineSource(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "ntx-001",
                        UUID.randomUUID(),
                        "card-123",
                        new Money(new BigDecimal("1500"), Currency.getInstance("JPY")),
                        Instant.parse("2026-06-15T10:00:00Z")
                )),
                new Money(new BigDecimal("1000"), Currency.getInstance("JPY")),
                Instant.parse("2026-07-01T00:00:00Z")
        );
    }

    private String redisKey(UUID statementId) {
        return "mini-card:cache:statement-read-model-v2:" + statementId;
    }
}
