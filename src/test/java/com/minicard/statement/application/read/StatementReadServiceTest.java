package com.minicard.statement.application.read;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minicard.statement.application.StatementGenerationService;
import com.minicard.shared.domain.Money;
import com.minicard.statement.domain.Statement;
import com.minicard.statement.domain.StatementLineSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;
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
        // 默认让请求成为重建锁 winner（抢锁成功），走"回源 DB + 写回 L2"主路径；
        // loser/fail-open 路径由各自的用例显式覆盖这个 stub。
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        service = new StatementReadService(
                statementGenerationService,
                redisTemplate,
                objectMapper,
                new StatementReadCacheProperties(
                        Duration.ofSeconds(30),
                        1000L,
                        REMOTE_TTL,
                        REMOTE_TTL_JITTER,
                        Duration.ofSeconds(10),
                        true,
                        Duration.ofSeconds(2),
                        3,
                        Duration.ofMillis(5)
                ),
                new SimpleMeterRegistry(),
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
    // 测试目的：验证 L1/L2 miss 时回源 DB，并把结果放入 Caffeine L1。
    // variant：同 JVM 连续读两次，第二次应命中 L1；首次回源后还要用版本信封 CAS 写回 Redis L2。
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
        assertThat((String) args.get(0)).isEqualTo("0");                 // new statement starts at row version 0
        assertThat((String) args.get(1)).startsWith("0|").contains("\"id\"");  // 真实值信封，非墓碑
        long ttlMillis = Long.parseLong((String) args.get(2));
        assertThat(ttlMillis).isBetween(REMOTE_TTL.toMillis(), REMOTE_TTL.plus(REMOTE_TTL_JITTER).toMillis());
    }

    @Test
    // 测试目的：验证 L2 信封版本来自 statements.version，而不是 paidAmount 等业务字段。
    // variant：还款后 version=1、paidAmount=500，Redis envelope 必须以 "1|" 开头。
    void redisEnvelopeUsesStatementVersionInsteadOfPaidAmount() {
        Statement statement = statement();
        statement.applyRepayment(
                new Money(new BigDecimal("500"), Currency.getInstance("JPY")),
                Instant.parse("2026-07-10T00:00:00Z")
        );
        String redisKey = redisKey(statement.id());
        when(valueOperations.get(redisKey)).thenReturn(null);
        when(statementGenerationService.get(statement.id())).thenReturn(statement);

        StatementReadModel readModel = service.get(statement.id());

        assertThat(readModel.paidAmount()).isEqualByComparingTo("500");
        assertThat(readModel.version()).isEqualTo(1);
        ArgumentCaptor<Object> casArgs = ArgumentCaptor.forClass(Object.class);
        verify(redisTemplate).execute(any(RedisScript.class), eq(List.of(redisKey)),
                casArgs.capture(), casArgs.capture(), casArgs.capture());
        List<Object> args = casArgs.getAllValues();
        assertThat((String) args.get(0)).isEqualTo("1");
        assertThat((String) args.get(1)).startsWith("1|").contains("\"paidAmount\":500");
    }

    @Test
    // 测试目的：验证 Redis L2 命中时不访问 MySQL source of truth。
    // variant：L2 存在 "<version>|json" 信封，read service 直接反序列化返回。
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
    // 测试目的：验证 tombstone 只是版本地板，不是可返回的真实缓存值。
    // variant：L2 值为 "100|"，读路径应当作 miss 回源 DB，再写回真实值。
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
    // 测试目的：验证写路径只在事务提交后失效缓存，避免 rollback 后误删/误广播。
    // variant：事务内调用 evictAfterCommit，commit 前不动 L2；afterCommit 写 tombstone 并广播 L1 失效。
    void evictInsideTransactionWritesTombstoneAndBroadcastsAfterCommit() {
        Statement statement = statement();
        statement.applyRepayment(
                new Money(new BigDecimal("500"), Currency.getInstance("JPY")),
                Instant.parse("2026-07-10T00:00:00Z")
        );
        String key = redisKey(statement.id());
        TransactionSynchronizationManager.initSynchronization();

        service.evictAfterCommit(statement);

        // commit 前：不写 L2、不广播。
        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(), any(), any(), any());
        verify(broadcaster, never()).broadcastEvict(statement.id());

        TransactionSynchronizationManager.getSynchronizations()
                .forEach(synchronization -> synchronization.afterCommit());

        // afterCommit：写"版本地板"墓碑（CAS，信封 "1|" 末尾为空 = tombstone）+ 广播给其他 pod。
        ArgumentCaptor<Object> casArgs = ArgumentCaptor.forClass(Object.class);
        verify(redisTemplate).execute(any(RedisScript.class), eq(List.of(key)),
                casArgs.capture(), casArgs.capture(), casArgs.capture());
        assertThat((String) casArgs.getAllValues().get(0)).isEqualTo("1");
        assertThat((String) casArgs.getAllValues().get(1)).isEqualTo("1|");
        verify(broadcaster).broadcastEvict(statement.id());
    }

    @Test
    // 测试目的：验证收到跨 pod 广播后只清本地 L1，不再触碰 L2 或二次广播。
    // variant：invalidateLocal 是订阅者动作，不能形成广播风暴。
    void invalidateLocalOnlyTouchesL1NotL2OrBroadcast() {
        // 订阅者收到其他 pod 的失效广播时只清本地 L1：绝不能再写 L2 或再广播，否则会形成广播风暴。
        UUID statementId = UUID.randomUUID();

        service.invalidateLocal(statementId);

        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(), any(), any(), any());
        verify(redisTemplate, never()).delete(anyString());
        verify(broadcaster, never()).broadcastEvict(statementId);
    }

    @Test
    // 测试目的：验证 rebuild-lock winner 回源 DB 后释放 Redis 锁。
    // variant：抢锁成功，读 DB、写 L2 后执行 release script，避免锁自然过期前阻塞其他请求。
    void rebuildLockWinnerReleasesLockAfterRebuild() {
        // 抢到锁的 winner 回源 DB 后必须释放锁：execute(RELEASE_SCRIPT, [lockKey], token)。
        Statement statement = statement();
        when(valueOperations.get(redisKey(statement.id()))).thenReturn(null);   // L2 miss
        when(statementGenerationService.get(statement.id())).thenReturn(statement);

        service.get(statement.id());

        // 释放锁的 keys 是 rebuild-lock key、只有一个 ARGV(token)，与 3-arg 的 CAS 写互不混淆。
        verify(redisTemplate).execute(
                any(RedisScript.class), eq(List.of(rebuildLockKey(statement.id()))), anyString());
        verify(statementGenerationService, times(1)).get(statement.id());
    }

    @Test
    // 测试目的：验证 rebuild-lock loser 等待 winner 填充 L2 后复用缓存。
    // variant：抢锁失败后自旋期间 L2 变为命中，loser 不应再回源 DB。
    void rebuildLockLoserWaitsThenReusesL2WithoutHittingDb() throws Exception {
        // loser 没抢到锁：自旋等待期间 winner 把 L2 填好，loser 直接复用，不回源 DB（防击穿核心收益）。
        Statement statement = statement();
        String redisKey = redisKey(statement.id());
        StatementReadModel model = StatementReadModel.from(statement);
        String envelope = model.version() + "|" + objectMapper.writeValueAsString(model);
        // 第一次读 L2 仍空，自旋一次后 winner 已写入。
        when(valueOperations.get(redisKey)).thenReturn(null, envelope);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);

        StatementReadModel readModel = service.get(statement.id());

        assertThat(readModel.id()).isEqualTo(statement.id());
        verify(statementGenerationService, never()).get(statement.id());
    }

    @Test
    // 测试目的：验证 winner 未填充 L2 时 loser 会 fail-open 回源。
    // variant：等待次数耗尽后自己读 DB，避免请求无限等在重建锁上。
    void rebuildLockLoserFailsOpenToDbWhenWinnerNeverPopulatesL2() {
        // winner 太慢/已死：loser 自旋耗尽后 fail-open 自己回源，绝不无限等在锁上。
        Statement statement = statement();
        when(valueOperations.get(redisKey(statement.id()))).thenReturn(null);   // L2 始终空
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        when(statementGenerationService.get(statement.id())).thenReturn(statement);

        StatementReadModel readModel = service.get(statement.id());

        assertThat(readModel.id()).isEqualTo(statement.id());
        verify(statementGenerationService, times(1)).get(statement.id());
    }

    @Test
    // 测试目的：验证 Redis 锁不可用时缓存击穿保护 fail-open。
    // variant：setIfAbsent 抛 RedisConnectionFailureException，GET 仍回源 DB 返回。
    void rebuildLockAcquireFailureFailsOpenToDb() {
        // 抢锁时 Redis 不可用：fail-open 回源，不让"击穿保护"本身变成 GET 的硬依赖。
        Statement statement = statement();
        when(valueOperations.get(redisKey(statement.id()))).thenReturn(null);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RedisConnectionFailureException("redis down"));
        when(statementGenerationService.get(statement.id())).thenReturn(statement);

        StatementReadModel readModel = service.get(statement.id());

        assertThat(readModel.id()).isEqualTo(statement.id());
        verify(statementGenerationService, times(1)).get(statement.id());
    }

    private Statement statement() {
        return Statement.close(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                LocalDate.parse("2026-06-01"),
                LocalDate.parse("2026-06-30"),
                LocalDate.parse("2026-07-25"),
                List.of(new StatementLineSource(
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
        return "mini-card:cache:statement-read-model-v3:" + statementId;
    }

    private String rebuildLockKey(UUID statementId) {
        return "mini-card:cache:statement-read-model-v3:rebuild-lock:" + statementId;
    }
}
