package com.minicard.messaging.outbox;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.core.task.SyncTaskExecutor;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxPollerTest {

    private static final Instant NOW = Instant.parse("2026-06-16T00:00:00Z");

    @Test
    // 测试目的：验证 poller 只负责调 claimer 并把已 claim event 交给 worker executor。
    // variant：使用 SyncTaskExecutor 让异步提交同步执行，便于断言 worker 被调用。
    void submitsClaimedEventsToWorkerExecutor() {
        OutboxClaimer claimer = mock(OutboxClaimer.class);
        OutboxWorker worker = mock(OutboxWorker.class);
        OutboxEvent event = pendingEvent();
        when(claimer.claimPublishableEvents()).thenReturn(List.of(event));
        OutboxPoller poller = new OutboxPoller(
                claimer,
                worker,
                new SyncTaskExecutor()
        );

        poller.pollPublishableEvents();

        verify(claimer).claimPublishableEvents();
        verify(worker).publishClaimedEvent(event);
    }

    private OutboxEvent pendingEvent() {
        return OutboxEvent.pending(
                UUID.randomUUID(),
                "Authorization",
                UUID.randomUUID().toString(),
                "authorization.approved",
                1,
                UUID.randomUUID().toString(),
                "{\"eventType\":\"authorization.approved\"}",
                NOW
        );
    }

}
