package com.minicard.notification.domain;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationTest {

    private static final Instant NOW = Instant.parse("2026-06-14T00:00:00Z");

    @Test
    void exhaustsDeliveryAttemptsInsideAggregate() {
        Notification notification = notification();

        notification.recordDeliveryFailure("provider timeout", 2, NOW.plusSeconds(1));
        notification.recordDeliveryFailure("provider timeout", 2, NOW.plusSeconds(2));

        assertThat(notification.status()).isEqualTo(NotificationStatus.FAILED);
        assertThat(notification.deliveryAttempts()).isEqualTo(2);
        assertThatThrownBy(() -> notification.markSent(NOW.plusSeconds(3)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void successfulDeliveryIsIdempotent() {
        Notification notification = notification();
        Instant sentAt = NOW.plusSeconds(1);

        notification.markSent(sentAt);
        notification.markSent(NOW.plusSeconds(2));

        assertThat(notification.status()).isEqualTo(NotificationStatus.SENT);
        assertThat(notification.sentAt()).isEqualTo(sentAt);
    }

    private Notification notification() {
        return Notification.requestAuthorizationDecision(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "card-123",
                true,
                NOW
        );
    }
}
