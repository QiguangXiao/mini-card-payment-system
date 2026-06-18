package com.minicard.delayjob;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.core.task.SyncTaskExecutor;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DelayJobPollerTest {

    @Test
    void submitsClaimedJobsToWorkerExecutor() {
        DelayJobClaimer claimer = mock(DelayJobClaimer.class);
        DelayJobWorker worker = mock(DelayJobWorker.class);
        DelayJob job = DelayJob.pending(
                UUID.randomUUID(),
                DelayJobType.AUTHORIZATION_EXPIRY,
                "Authorization",
                UUID.randomUUID().toString(),
                Instant.parse("2026-06-08T00:00:00Z"),
                Instant.parse("2026-06-08T00:00:00Z")
        );
        when(claimer.claimDueJobs()).thenReturn(List.of(job));
        DelayJobPoller poller = new DelayJobPoller(
                claimer,
                worker,
                new SyncTaskExecutor()
        );

        poller.pollDueJobs();

        verify(claimer).claimDueJobs();
        verify(worker).handleClaimedJob(job);
    }
}
