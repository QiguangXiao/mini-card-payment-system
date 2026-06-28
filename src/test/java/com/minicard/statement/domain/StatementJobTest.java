package com.minicard.statement.domain;

import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StatementJobTest {

    private static final Instant NOW = Instant.parse("2026-07-01T00:00:00Z");
    private static final LocalDate PERIOD_START = LocalDate.parse("2026-06-01");
    private static final LocalDate PERIOD_END = LocalDate.parse("2026-06-30");
    private static final LocalDate DUE_DATE = LocalDate.parse("2026-07-27");

    @Test
    void claimCreatesProcessingLeaseAndDoneClearsIt() {
        StatementJob job = pendingShard(0, 4);

        job.markProcessing("worker-1", NOW.plusSeconds(1), 300);

        assertThat(job.status()).isEqualTo(StatementJobStatus.PROCESSING);
        assertThat(job.claimedBy()).isEqualTo("worker-1");
        assertThat(job.claimedAt()).isEqualTo(NOW.plusSeconds(1));
        assertThat(job.claimUntil()).isEqualTo(NOW.plusSeconds(301));
        assertThat(job.claimToken()).isNotBlank();
        assertThat(job.attemptCount()).isEqualTo(1);

        job.markDone(new StatementJobExecutionResult(10, 8, 2, 0), NOW.plusSeconds(2));

        assertThat(job.status()).isEqualTo(StatementJobStatus.DONE);
        assertThat(job.claimedBy()).isNull();
        assertThat(job.claimedAt()).isNull();
        assertThat(job.claimUntil()).isNull();
        assertThat(job.claimToken()).isNull();
        assertThat(job.generatedStatementCount()).isEqualTo(8);
    }

    @Test
    void failedJobRetriesUntilMaxAttemptsThenDead() {
        StatementJob job = pendingShard(0, 1);

        job.markProcessing("worker-1", NOW.plusSeconds(1), 300);
        String firstClaimToken = job.claimToken();
        job.markFailed(new StatementJobExecutionResult(10, 9, 0, 1), "temporary", NOW.plusSeconds(2), 2);

        assertThat(job.status()).isEqualTo(StatementJobStatus.PENDING);
        assertThat(job.claimToken()).isNull();
        assertThat(job.failedAccountCount()).isEqualTo(1);

        job.markProcessing("worker-2", NOW.plusSeconds(3), 300);
        assertThat(job.claimToken()).isNotBlank();
        assertThat(job.claimToken()).isNotEqualTo(firstClaimToken);
        job.markFailed(new StatementJobExecutionResult(10, 9, 0, 1), "still broken", NOW.plusSeconds(4), 2);

        assertThat(job.status()).isEqualTo(StatementJobStatus.DEAD);
        assertThat(job.claimedBy()).isNull();
        assertThat(job.claimToken()).isNull();
        assertThat(job.lastError()).isEqualTo("still broken");
    }

    private StatementJob pendingShard(int shardNo, int shardCount) {
        return StatementJob.pending(PERIOD_START, PERIOD_END, DUE_DATE, shardNo, shardCount, NOW);
    }
}
