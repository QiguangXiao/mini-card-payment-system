package com.minicard.messaging.outbox;

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

class OutboxStuckEventRecovererTest {

    private static final Instant NOW = Instant.parse("2026-06-16T00:00:00Z");

    @Test
    void recoversExpiredProcessingLeaseForRetry() {
        OutboxEventRepository repository = mock(OutboxEventRepository.class);
        OutboxEvent event = pendingEvent();
        event.markProcessing(NOW.minusSeconds(60), 30);
        when(repository.findStuckProcessingBatchForUpdate(NOW, 10)).thenReturn(List.of(event));
        OutboxStuckEventRecoverer recoverer = new OutboxStuckEventRecoverer(
                repository,
                properties(),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );

        recoverer.recoverStuckEvents();

        assertThat(event.status()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(event.attempts()).isEqualTo(1);
        assertThat(event.lastError()).isEqualTo("outbox processing lease expired");
        verify(repository).updateDeliveryState(event);
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

    private OutboxPublisherProperties properties() {
        return new OutboxPublisherProperties(true, 1000, 5000, 10, 5000, 30, 3, 4, 100);
    }
}
