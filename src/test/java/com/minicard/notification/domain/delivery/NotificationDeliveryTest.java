package com.minicard.notification.domain.delivery;

import java.time.Instant;
import java.util.UUID;

import com.minicard.notification.domain.Notification;
import com.minicard.notification.domain.NotificationSubjectType;
import com.minicard.notification.domain.NotificationType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationDeliveryTest {

    private static final Instant NOW = Instant.parse("2026-06-14T00:00:00Z");

    @Test
    void pendingForSnapshotsNotificationFields() {
        NotificationDelivery delivery = pushDelivery();

        assertThat(delivery.status()).isEqualTo(NotificationDeliveryStatus.PENDING);
        assertThat(delivery.attempts()).isZero();
        assertThat(delivery.nextAttemptAt()).isEqualTo(NOW);
        assertThat(delivery.channel()).isEqualTo(NotificationChannel.APP_PUSH);
        assertThat(delivery.notificationType()).isEqualTo(NotificationType.CARD_TRANSACTION_POSTED);
        assertThat(delivery.subjectId()).isEqualTo("txn-1");
        assertThat(delivery.recipientKey()).isEqualTo("card-1");
        // 幂等键稳定且等于 delivery id：透传给 provider 做去重的依据。
        assertThat(delivery.idempotencyKey()).isEqualTo(delivery.id().toString());
    }

    @Test
    void leaseThenSentReachesTerminalSuccess() {
        NotificationDelivery delivery = pushDelivery();

        delivery.markProcessing(NOW, 30);
        assertThat(delivery.status()).isEqualTo(NotificationDeliveryStatus.PROCESSING);
        // PROCESSING 复用 nextAttemptAt 作为 lease deadline。
        assertThat(delivery.nextAttemptAt()).isEqualTo(NOW.plusSeconds(30));

        delivery.markSent(NOW.plusSeconds(1), "push-abc");
        assertThat(delivery.status()).isEqualTo(NotificationDeliveryStatus.SENT);
        assertThat(delivery.sentAt()).isEqualTo(NOW.plusSeconds(1));
        assertThat(delivery.providerMessageId()).isEqualTo("push-abc");
        assertThat(delivery.lastError()).isNull();
    }

    @Test
    void failureBacksOffThenGoesDeadAtMaxAttempts() {
        NotificationDelivery delivery = pushDelivery();

        delivery.markFailed("boom", NOW.plusSeconds(1), 2);
        assertThat(delivery.status()).isEqualTo(NotificationDeliveryStatus.PENDING);
        assertThat(delivery.attempts()).isEqualTo(1);
        // 退避：2^(attempts-1)=1s，nextAttemptAt = failedAt + 1s。
        assertThat(delivery.nextAttemptAt()).isEqualTo(NOW.plusSeconds(2));
        assertThat(delivery.lastError()).isEqualTo("boom");

        delivery.markFailed("boom again", NOW.plusSeconds(5), 2);
        assertThat(delivery.status()).isEqualTo(NotificationDeliveryStatus.DEAD);
        assertThat(delivery.attempts()).isEqualTo(2);
    }

    @Test
    void processingTimeoutCountsAsOneFailure() {
        NotificationDelivery delivery = pushDelivery();
        delivery.markProcessing(NOW, 30);

        delivery.markProcessingTimedOut(NOW.plusSeconds(31), 5);

        assertThat(delivery.attempts()).isEqualTo(1);
        assertThat(delivery.status()).isEqualTo(NotificationDeliveryStatus.PENDING);
        assertThat(delivery.lastError()).contains("lease expired");
    }

    private NotificationDelivery pushDelivery() {
        return NotificationDelivery.pendingFor(intent(), NotificationChannel.APP_PUSH, NOW);
    }

    private Notification intent() {
        return Notification.requestFromEvent(
                UUID.randomUUID(),
                NotificationSubjectType.CARD_TRANSACTION,
                "txn-1",
                "card-1",
                NotificationType.CARD_TRANSACTION_POSTED,
                NOW
        );
    }
}
