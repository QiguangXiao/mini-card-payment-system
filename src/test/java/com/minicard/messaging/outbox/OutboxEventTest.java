package com.minicard.messaging.outbox;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxEventTest {

    private static final Instant NOW = Instant.parse("2026-06-14T00:00:00Z");

    @Test
    void marksEventProcessingWithLeaseDeadline() {
        OutboxEvent event = pendingEvent();

        event.markProcessing(NOW, 30, "lease-token-1");

        assertThat(event.status()).isEqualTo(OutboxEventStatus.PROCESSING);
        assertThat(event.nextAttemptAt()).isEqualTo(NOW.plusSeconds(30));
        assertThat(event.leaseToken()).isEqualTo("lease-token-1");
    }

    @Test
    void retriesWithExponentialBackoffBeforeBecomingDead() {
        OutboxEvent event = pendingEvent();

        event.markFailed("Kafka unavailable", NOW, 3);
        assertThat(event.status()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(event.attempts()).isEqualTo(1);
        assertThat(event.nextAttemptAt()).isEqualTo(NOW.plusSeconds(1));

        event.markFailed("Kafka unavailable", NOW.plusSeconds(1), 3);
        assertThat(event.nextAttemptAt()).isEqualTo(NOW.plusSeconds(3));

        event.markFailed("Kafka unavailable", NOW.plusSeconds(3), 3);
        assertThat(event.status()).isEqualTo(OutboxEventStatus.DEAD);
        assertThat(event.attempts()).isEqualTo(3);
    }

    @Test
    void marksAcknowledgedEventPublished() {
        OutboxEvent event = pendingEvent();
        event.markProcessing(NOW, 30, "lease-token-1");

        event.markPublished(NOW.plusSeconds(1));

        assertThat(event.status()).isEqualTo(OutboxEventStatus.PUBLISHED);
        assertThat(event.publishedAt()).isEqualTo(NOW.plusSeconds(1));
        assertThat(event.lastError()).isNull();
        assertThat(event.leaseToken()).isNull();
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
