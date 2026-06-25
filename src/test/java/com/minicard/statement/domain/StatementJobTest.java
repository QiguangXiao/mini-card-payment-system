package com.minicard.statement.domain;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StatementJobTest {

    private static final Instant NOW = Instant.parse("2026-07-01T00:00:00Z");

    @Test
    void claimCreatesProcessingLeaseAndDoneClearsIt() {
        StatementJob job = StatementJob.pending(UUID.randomUUID(), 0, 4, NOW);

        job.markProcessing("worker-1", NOW.plusSeconds(1), 300);

        assertThat(job.status()).isEqualTo(StatementJobStatus.PROCESSING);
        assertThat(job.claimedBy()).isEqualTo("worker-1");
        assertThat(job.claimUntil()).isEqualTo(NOW.plusSeconds(301));
        assertThat(job.attemptCount()).isEqualTo(1);

        job.markDone(new StatementJobExecutionResult(10, 8, 2, 0), NOW.plusSeconds(2));

        assertThat(job.status()).isEqualTo(StatementJobStatus.DONE);
        assertThat(job.claimedBy()).isNull();
        assertThat(job.claimUntil()).isNull();
        assertThat(job.generatedStatementCount()).isEqualTo(8);
    }

    @Test
    void failedJobRetriesUntilMaxAttemptsThenDead() {
        StatementJob job = StatementJob.pending(UUID.randomUUID(), 0, 1, NOW);

        job.markProcessing("worker-1", NOW.plusSeconds(1), 300);
        job.markFailed(new StatementJobExecutionResult(10, 9, 0, 1), "temporary", NOW.plusSeconds(2), 2);

        assertThat(job.status()).isEqualTo(StatementJobStatus.PENDING);
        assertThat(job.failedAccountCount()).isEqualTo(1);

        job.markProcessing("worker-2", NOW.plusSeconds(3), 300);
        job.markFailed(new StatementJobExecutionResult(10, 9, 0, 1), "still broken", NOW.plusSeconds(4), 2);

        assertThat(job.status()).isEqualTo(StatementJobStatus.DEAD);
        assertThat(job.claimedBy()).isNull();
        assertThat(job.lastError()).isEqualTo("still broken");
    }
}
