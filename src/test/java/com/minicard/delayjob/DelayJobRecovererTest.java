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

class DelayJobRecovererTest {

    private static final Instant NOW = Instant.parse("2026-06-08T00:00:00Z");

    @Test
    // 测试目的：验证 recoverer 会把过期 PROCESSING lease 当作一次 job failure。
    // variant：超时 job 回 PENDING、attempts+1、leaseToken 清空，后续可重新执行 future business action。
    void recoversExpiredProcessingLeaseForRetry() {
        DelayJobRepository repository = mock(DelayJobRepository.class);
        DelayJob job = job();
        job.markProcessing(NOW.minusSeconds(120), 60, "lease-token-1");
        when(repository.findStuckProcessingBatchForUpdate(NOW, 100)).thenReturn(List.of(job));
        DelayJobRecoverer recoverer = new DelayJobRecoverer(
                repository,
                new DelayJobProperties(true, 1000, 5000, 100, 3, 60, 4, 100, 4, 100),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );

        recoverer.recoverStuckJobs();

        assertThat(job.status()).isEqualTo(DelayJobStatus.PENDING);
        assertThat(job.attempts()).isEqualTo(1);
        assertThat(job.leaseToken()).isNull();
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
