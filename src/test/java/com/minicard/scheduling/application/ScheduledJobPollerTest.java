package com.minicard.scheduling.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.minicard.scheduling.domain.DelayJob;
import com.minicard.scheduling.domain.DelayJobType;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SyncTaskExecutor;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScheduledJobPollerTest {

    @Test
    void submitsClaimedJobsToWorkerExecutor() {
        ScheduledJobClaimService claimService = mock(ScheduledJobClaimService.class);
        ScheduledJobWorker worker = mock(ScheduledJobWorker.class);
        DelayJob job = DelayJob.pending(
                UUID.randomUUID(),
                DelayJobType.AUTHORIZATION_EXPIRY,
                "Authorization",
                UUID.randomUUID().toString(),
                Instant.parse("2026-06-08T00:00:00Z"),
                Instant.parse("2026-06-08T00:00:00Z")
        );
        when(claimService.claimDueJobs()).thenReturn(List.of(job));
        ScheduledJobPoller poller = new ScheduledJobPoller(
                claimService,
                worker,
                new SyncTaskExecutor()
        );

        poller.pollDueJobs();

        verify(claimService).claimDueJobs();
        verify(worker).handleClaimedJob(job);
    }
}
