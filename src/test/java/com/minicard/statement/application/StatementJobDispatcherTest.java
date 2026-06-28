package com.minicard.statement.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.minicard.statement.domain.StatementJob;
import com.minicard.statement.domain.StatementJobExecutionResult;
import com.minicard.statement.domain.StatementJobStatus;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StatementJobDispatcherTest {

    private static final Instant NOW = Instant.parse("2026-07-01T00:00:00Z");
    private static final LocalDate PERIOD_START = LocalDate.parse("2026-06-01");
    private static final LocalDate PERIOD_END = LocalDate.parse("2026-06-30");
    private static final LocalDate DUE_DATE = LocalDate.parse("2026-07-27");

    private final StatementJobRepository jobRepository = mock(StatementJobRepository.class);
    private final StatementJobHandler handler = mock(StatementJobHandler.class);
    private final TransactionOperations transactionOperations = new TransactionOperations() {
        @Override
        public <T> T execute(TransactionCallback<T> action) {
            return action.doInTransaction(null);
        }
    };
    private final TaskExecutor directExecutor = Runnable::run;
    private final StatementJobDispatcher dispatcher = new StatementJobDispatcher(
            jobRepository,
            handler,
            properties(),
            Clock.fixed(NOW, ZoneOffset.UTC),
            transactionOperations,
            directExecutor
    );

    @Test
    void staleWorkerDoesNotFinalizeWhenClaimTokenChanged() {
        StatementJob claimed = StatementJob.pending(PERIOD_START, PERIOD_END, DUE_DATE, 0, 1, NOW);
        when(jobRepository.findClaimableBatchForUpdate(any(), anyInt())).thenReturn(List.of(claimed));
        when(handler.handle(any())).thenReturn(new StatementJobExecutionResult(10, 10, 0, 0));
        when(jobRepository.findByIdForUpdate(claimed.id()))
                .thenAnswer(invocation -> Optional.of(currentLeaseWithDifferentToken(claimed)));

        dispatcher.dispatch();

        // 第一次 update 是 claim 事务写入 PROCESSING；finalize 看到 claim_token 已变，不能再写 DONE。
        verify(jobRepository, times(1)).updateExecutionState(any(StatementJob.class));
    }

    private StatementJob currentLeaseWithDifferentToken(StatementJob claimed) {
        return StatementJob.restore(
                claimed.id(),
                claimed.periodStart(),
                claimed.periodEnd(),
                claimed.dueDate(),
                claimed.shardNo(),
                claimed.shardCount(),
                StatementJobStatus.PROCESSING,
                claimed.claimedBy(),
                claimed.claimedAt(),
                claimed.claimUntil(),
                "different-claim-token",
                claimed.attemptCount(),
                claimed.processedAccountCount(),
                claimed.generatedStatementCount(),
                claimed.skippedAccountCount(),
                claimed.failedAccountCount(),
                claimed.createdAt(),
                claimed.updatedAt(),
                claimed.lastError()
        );
    }

    private StatementProperties properties() {
        return new StatementProperties(
                new StatementProperties.Batch(true, "0 0 1 * * *", "Asia/Tokyo", 31, 27, 1000),
                new StatementProperties.Jobs(true, 1000, 10000, 10, 3, 300, 1, 10),
                new StatementProperties.Policy(new BigDecimal("0.10"), Map.of("JPY", new BigDecimal("1000.00")))
        );
    }
}
