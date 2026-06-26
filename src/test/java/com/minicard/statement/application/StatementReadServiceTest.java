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
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
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
    private StatementReadService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        statementGenerationService = mock(StatementGenerationService.class);
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        objectMapper = new ObjectMapper().findAndRegisterModules();
        service = new StatementReadService(
                statementGenerationService,
                redisTemplate,
                objectMapper,
                new StatementReadCacheProperties(
                        Duration.ofSeconds(30),
                        1000L,
                        REMOTE_TTL,
                        REMOTE_TTL_JITTER
                )
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
        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(valueOperations).set(eq(redisKey), anyString(), ttl.capture());
        assertThat(ttl.getValue()).isBetween(REMOTE_TTL, REMOTE_TTL.plus(REMOTE_TTL_JITTER));
    }

    @Test
    void loadsFromRedisL2WithoutCallingDb() throws Exception {
        Statement statement = statement();
        String redisKey = redisKey(statement.id());
        when(valueOperations.get(redisKey))
                .thenReturn(objectMapper.writeValueAsString(StatementReadModel.from(statement)));

        StatementReadModel readModel = service.get(statement.id());

        assertThat(readModel.id()).isEqualTo(statement.id());
        verify(statementGenerationService, never()).get(statement.id());
    }

    @Test
    void evictInsideTransactionRunsAfterCommit() {
        UUID statementId = UUID.randomUUID();
        TransactionSynchronizationManager.initSynchronization();

        service.evictAfterCommit(statementId);

        verify(redisTemplate, never()).delete(redisKey(statementId));
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(synchronization -> synchronization.afterCommit());
        verify(redisTemplate).delete(redisKey(statementId));
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
        return "mini-card:cache:statement-read-model-v1:" + statementId;
    }
}
