package com.minicard.messaging.outbox.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.minicard.messaging.outbox.domain.OutboxEvent;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SyncTaskExecutor;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxPublisherPollerTest {

    @Test
    void submitsClaimedEventsToWorkerExecutor() {
        OutboxClaimService claimService = mock(OutboxClaimService.class);
        OutboxWorker worker = mock(OutboxWorker.class);
        OutboxEvent event = pendingEvent();
        when(claimService.claimPublishableEvents()).thenReturn(List.of(event));
        OutboxPublisherPoller poller = new OutboxPublisherPoller(
                claimService,
                worker,
                new SyncTaskExecutor()
        );

        poller.pollPublishableEvents();

        verify(claimService).claimPublishableEvents();
        verify(worker).publishClaimedEvent(event);
    }

    private OutboxEvent pendingEvent() {
        Instant now = Instant.parse("2026-06-16T00:00:00Z");
        return OutboxEvent.pending(
                UUID.randomUUID(),
                "Authorization",
                UUID.randomUUID().toString(),
                "authorization.approved",
                1,
                UUID.randomUUID().toString(),
                "{\"eventType\":\"authorization.approved\"}",
                now
        );
    }
}
