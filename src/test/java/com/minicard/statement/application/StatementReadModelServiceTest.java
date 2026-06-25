package com.minicard.statement.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import com.minicard.authorization.domain.Money;
import com.minicard.infrastructure.cache.SnapshotCache;
import com.minicard.infrastructure.cache.TransactionAwareSnapshotCacheEvictor;
import com.minicard.statement.domain.Statement;
import com.minicard.statement.domain.StatementLine;
import com.minicard.statement.domain.StatementLineSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StatementReadServiceTest {

    private static final UUID STATEMENT_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ACCOUNT_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    private StatementGenerationService statementGenerationService;
    private FakeSnapshotCache cache;
    private StatementReadService service;

    @BeforeEach
    void setUp() {
        statementGenerationService = mock(StatementGenerationService.class);
        cache = new FakeSnapshotCache();
        service = new StatementReadService(
                statementGenerationService,
                cache,
                new TransactionAwareSnapshotCacheEvictor()
        );
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void cachesStatementReadModelInsteadOfReloadingAggregateEveryTime() {
        Statement statement = statement();
        when(statementGenerationService.get(statement.id())).thenReturn(statement);

        StatementReadModel first = service.get(statement.id());
        StatementReadModel second = service.get(statement.id());

        assertThat(first).isSameAs(second);
        assertThat(first.status()).isEqualTo("CLOSED");
        assertThat(first.items()).hasSize(1);
        verify(statementGenerationService, times(1)).get(statement.id());
    }

    @Test
    void evictsImmediatelyWhenNoTransactionSynchronizationExists() {
        service.evictAfterCommit(STATEMENT_ID);

        assertThat(cache.evictedKeys).containsExactly(STATEMENT_ID);
    }

    @Test
    void evictsOnlyAfterTransactionCommitWhenSynchronizationExists() {
        TransactionSynchronizationManager.initSynchronization();

        service.evictAfterCommit(STATEMENT_ID);

        assertThat(cache.evictedKeys).isEmpty();
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(synchronization -> synchronization.afterCommit());
        assertThat(cache.evictedKeys).containsExactly(STATEMENT_ID);
    }

    private Statement statement() {
        Statement statement = Statement.close(
                ACCOUNT_ID,
                LocalDate.parse("2026-06-01"),
                LocalDate.parse("2026-06-30"),
                LocalDate.parse("2026-07-25"),
                List.of(new StatementLineSource(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "ntx-001",
                        UUID.randomUUID(),
                        "card-123",
                        money("1500.00"),
                        Instant.parse("2026-06-15T10:00:00Z")
                )),
                money("1000.00"),
                Instant.parse("2026-07-01T00:00:00Z")
        );
        return Statement.restore(
                STATEMENT_ID,
                statement.creditAccountId(),
                statement.periodStart(),
                statement.periodEnd(),
                statement.dueDate(),
                statement.totalAmount(),
                statement.minimumPaymentAmount(),
                statement.paidAmount(),
                statement.transactionCount(),
                statement.status(),
                statement.generatedAt(),
                statement.createdAt(),
                statement.updatedAt(),
                statement.items().stream()
                        .map(item -> StatementLine.restore(
                                item.id(),
                                STATEMENT_ID,
                                item.cardTransactionId(),
                                item.ledgerEntryId().orElse(null),
                                item.networkTransactionId(),
                                item.authorizationId(),
                                item.cardId(),
                                item.amount(),
                                item.postedAt(),
                                item.createdAt()
                        ))
                        .toList()
        );
    }

    private Money money(String amount) {
        return new Money(new BigDecimal(amount), Currency.getInstance("JPY"));
    }

    private static final class FakeSnapshotCache
            implements SnapshotCache<UUID, StatementReadModel> {

        private final Map<UUID, StatementReadModel> values = new HashMap<>();
        private final List<UUID> evictedKeys = new ArrayList<>();

        @Override
        public StatementReadModel get(UUID key, Supplier<StatementReadModel> loader) {
            return values.computeIfAbsent(key, ignored -> loader.get());
        }

        @Override
        public void evict(UUID key) {
            values.remove(key);
            evictedKeys.add(key);
        }
    }
}
