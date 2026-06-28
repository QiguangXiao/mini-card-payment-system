package com.minicard.delayjob;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DelayJobWorkerTest {

    private static final Instant NOW = Instant.parse("2026-06-08T00:00:00Z");

    @Test
    void handlesClaimedJobAndMarksItDone() {
        DelayJobRepository repository = mock(DelayJobRepository.class);
        DelayJobHandler handler = mock(DelayJobHandler.class);
        DelayJob job = claimedJob();
        when(handler.jobType()).thenReturn(DelayJobType.AUTHORIZATION_EXPIRY);
        when(repository.findByIdForUpdate(job.id())).thenReturn(Optional.of(job));
        DelayJobWorker worker = worker(repository, handler);

        worker.handleClaimedJob(job);

        verify(handler).handle(job);
        assertThat(job.status()).isEqualTo(DelayJobStatus.DONE);
        verify(repository).updateExecutionState(job);
    }

    @Test
    void failedJobIsKeptPendingUntilMaxAttempts() {
        DelayJobRepository repository = mock(DelayJobRepository.class);
        DelayJobHandler handler = mock(DelayJobHandler.class);
        DelayJob job = claimedJob();
        when(handler.jobType()).thenReturn(DelayJobType.AUTHORIZATION_EXPIRY);
        when(repository.findByIdForUpdate(job.id())).thenReturn(Optional.of(job));
        org.mockito.Mockito.doThrow(new IllegalStateException("not ready"))
                .when(handler).handle(job);
        DelayJobWorker worker = worker(repository, handler);

        worker.handleClaimedJob(job);

        assertThat(job.status()).isEqualTo(DelayJobStatus.PENDING);
        assertThat(job.attempts()).isEqualTo(1);
        assertThat(job.nextAttemptAt()).isAfter(NOW);
        verify(repository).updateExecutionState(job);
    }

    @Test
    void staleWorkerDoesNotOverwriteChangedLease() {
        DelayJobRepository repository = mock(DelayJobRepository.class);
        DelayJobHandler handler = mock(DelayJobHandler.class);
        DelayJob claimed = claimedJob();
        DelayJob current = claimedJob();
        // deadline 相同但 token 不同：证明 finalize ownership 不再依赖 nextAttemptAt timestamp。
        current.markProcessing(NOW, 60, "lease-token-2");
        when(handler.jobType()).thenReturn(DelayJobType.AUTHORIZATION_EXPIRY);
        when(repository.findByIdForUpdate(claimed.id())).thenReturn(Optional.of(current));
        DelayJobWorker worker = worker(repository, handler);

        worker.handleClaimedJob(claimed);

        verify(handler).handle(claimed);
        verify(repository, never()).updateExecutionState(current);
    }

    private DelayJobWorker worker(
            DelayJobRepository repository,
            DelayJobHandler handler
    ) {
        return new DelayJobWorker(
                repository,
                properties(),
                List.of(handler),
                Clock.fixed(NOW, ZoneOffset.UTC),
                transactionOperations()
        );
    }

    private DelayJob claimedJob() {
        DelayJob job = DelayJob.pending(
                UUID.randomUUID(),
                DelayJobType.AUTHORIZATION_EXPIRY,
                "Authorization",
                UUID.randomUUID().toString(),
                NOW,
                NOW
        );
        job.markProcessing(NOW, 60, "lease-token-1");
        return job;
    }

    private DelayJobProperties properties() {
        return new DelayJobProperties(true, 1000, 5000, 100, 3, 60, 4, 100);
    }

    private TransactionOperations transactionOperations() {
        return new TransactionOperations() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(new SimpleTransactionStatus());
            }
        };
    }
}
