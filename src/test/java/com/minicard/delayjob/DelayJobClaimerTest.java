package com.minicard.delayjob;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DelayJobClaimerTest {

    private static final Instant NOW = Instant.parse("2026-06-08T00:00:00Z");

    @Test
    void claimsDueJobsInShortTransaction() {
        DelayJobRepository repository = mock(DelayJobRepository.class);
        DelayJob job = job();
        when(repository.findRunnableBatchForUpdate(NOW, 100)).thenReturn(List.of(job));
        DelayJobClaimer claimer = new DelayJobClaimer(
                repository,
                properties(),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );

        List<DelayJob> claimed = claimer.claimDueJobs();

        assertThat(claimed).containsExactly(job);
        assertThat(job.status()).isEqualTo(DelayJobStatus.PROCESSING);
        assertThat(job.nextAttemptAt()).isEqualTo(NOW.plusSeconds(60));
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

    private DelayJobProperties properties() {
        return new DelayJobProperties(true, 1000, 5000, 100, 3, 60, 4, 100);
    }
}
