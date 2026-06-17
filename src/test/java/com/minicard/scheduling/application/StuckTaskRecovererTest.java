package com.minicard.scheduling.application;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.minicard.scheduling.domain.DelayJob;
import com.minicard.scheduling.domain.DelayJobRepository;
import com.minicard.scheduling.domain.DelayJobStatus;
import com.minicard.scheduling.domain.DelayJobType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StuckTaskRecovererTest {

    private static final Instant NOW = Instant.parse("2026-06-08T00:00:00Z");

    @Test
    void recoversExpiredProcessingLeaseForRetry() {
        DelayJobRepository repository = mock(DelayJobRepository.class);
        DelayJob job = job();
        job.markProcessing(NOW.minusSeconds(120), 60);
        when(repository.findStuckProcessingBatchForUpdate(NOW, 100)).thenReturn(List.of(job));
        StuckTaskRecoverer recoverer = new StuckTaskRecoverer(
                repository,
                new DelayJobSchedulerProperties(true, 1000, 5000, 100, 3, 60, 4, 100),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );

        recoverer.recoverStuckJobs();

        assertThat(job.status()).isEqualTo(DelayJobStatus.PENDING);
        assertThat(job.attempts()).isEqualTo(1);
        assertThat(job.lastError()).isEqualTo("processing lease expired");
        verify(repository).updateExecutionState(job);
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
}
