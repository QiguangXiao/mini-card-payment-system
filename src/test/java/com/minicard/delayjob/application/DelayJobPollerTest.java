package com.minicard.delayjob.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.minicard.delayjob.domain.DelayJob;
import com.minicard.delayjob.domain.DelayJobType;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SyncTaskExecutor;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DelayJobPollerTest {

    @Test
    void submitsClaimedJobsToWorkerExecutor() {
        DelayJobClaimService claimService = mock(DelayJobClaimService.class);
        DelayJobWorker worker = mock(DelayJobWorker.class);
        DelayJob job = DelayJob.pending(
                UUID.randomUUID(),
                DelayJobType.AUTHORIZATION_EXPIRY,
                "Authorization",
                UUID.randomUUID().toString(),
                Instant.parse("2026-06-08T00:00:00Z"),
                Instant.parse("2026-06-08T00:00:00Z")
        );
        when(claimService.claimDueJobs()).thenReturn(List.of(job));
        DelayJobPoller poller = new DelayJobPoller(
                claimService,
                worker,
                new SyncTaskExecutor()
        );

        poller.pollDueJobs();

        verify(claimService).claimDueJobs();
        verify(worker).handleClaimedJob(job);
    }
}
