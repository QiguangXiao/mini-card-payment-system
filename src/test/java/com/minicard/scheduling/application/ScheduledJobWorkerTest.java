package com.minicard.scheduling.application;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.minicard.scheduling.domain.DelayJob;
import com.minicard.scheduling.domain.DelayJobRepository;
import com.minicard.scheduling.domain.DelayJobStatus;
import com.minicard.scheduling.domain.DelayJobType;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScheduledJobWorkerTest {

    private static final Instant NOW = Instant.parse("2026-06-08T00:00:00Z");

    @Test
    void handlesClaimedJobAndMarksItDone() {
        DelayJobRepository repository = mock(DelayJobRepository.class);
        DelayJobHandler handler = mock(DelayJobHandler.class);
        DelayJob job = claimedJob();
        when(handler.jobType()).thenReturn(DelayJobType.AUTHORIZATION_EXPIRY);
        when(repository.findByIdForUpdate(job.id())).thenReturn(Optional.of(job));
        ScheduledJobWorker worker = worker(repository, handler);

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
        ScheduledJobWorker worker = worker(repository, handler);

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
        current.markProcessing(NOW.plusSeconds(1), 60);
        when(handler.jobType()).thenReturn(DelayJobType.AUTHORIZATION_EXPIRY);
        when(repository.findByIdForUpdate(claimed.id())).thenReturn(Optional.of(current));
        ScheduledJobWorker worker = worker(repository, handler);

        worker.handleClaimedJob(claimed);

        verify(handler).handle(claimed);
        verify(repository, never()).updateExecutionState(current);
    }

    private ScheduledJobWorker worker(
            DelayJobRepository repository,
            DelayJobHandler handler
    ) {
        return new ScheduledJobWorker(
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
        job.markProcessing(NOW, 60);
        return job;
    }

    private DelayJobSchedulerProperties properties() {
        return new DelayJobSchedulerProperties(true, 1000, 5000, 100, 3, 60, 4, 100);
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
