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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DelayJobServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-08T00:00:00Z");

    @Test
    void dispatchesDueJobAndMarksItDone() {
        DelayJobRepository repository = mock(DelayJobRepository.class);
        DelayJobHandler handler = mock(DelayJobHandler.class);
        DelayJob job = job();
        when(handler.jobType()).thenReturn(DelayJobType.AUTHORIZATION_EXPIRY);
        when(repository.findNextRunnableForUpdate(NOW)).thenReturn(Optional.of(job));
        when(repository.findByIdForUpdate(job.id())).thenReturn(Optional.of(job));
        DelayJobService service = new DelayJobService(
                repository,
                new DelayJobSchedulerProperties(true, 1000, 100, 3, 60),
                List.of(handler),
                Clock.fixed(NOW, ZoneOffset.UTC),
                transactionOperations()
        );

        assertThat(service.dispatchNext()).isTrue();

        verify(handler).handle(job);
        assertThat(job.status()).isEqualTo(DelayJobStatus.DONE);
        verify(repository, times(2)).updateExecutionState(job);
    }

    @Test
    void failedJobIsKeptPendingUntilMaxAttempts() {
        DelayJobRepository repository = mock(DelayJobRepository.class);
        DelayJobHandler handler = mock(DelayJobHandler.class);
        DelayJob job = job();
        when(handler.jobType()).thenReturn(DelayJobType.AUTHORIZATION_EXPIRY);
        when(repository.findNextRunnableForUpdate(NOW)).thenReturn(Optional.of(job));
        when(repository.findByIdForUpdate(job.id())).thenReturn(Optional.of(job));
        org.mockito.Mockito.doThrow(new IllegalStateException("not ready"))
                .when(handler).handle(job);
        DelayJobService service = new DelayJobService(
                repository,
                new DelayJobSchedulerProperties(true, 1000, 100, 3, 60),
                List.of(handler),
                Clock.fixed(NOW, ZoneOffset.UTC),
                transactionOperations()
        );

        assertThat(service.dispatchNext()).isTrue();

        assertThat(job.status()).isEqualTo(DelayJobStatus.PENDING);
        assertThat(job.attempts()).isEqualTo(1);
        assertThat(job.nextAttemptAt()).isAfter(NOW);
        verify(repository, times(2)).updateExecutionState(job);
    }

    private DelayJob job() {
        return DelayJob.pending(
                UUID.randomUUID(),
                DelayJobType.AUTHORIZATION_EXPIRY,
                "Authorization",
                UUID.randomUUID().toString(),
                NOW,
                NOW
        );
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
