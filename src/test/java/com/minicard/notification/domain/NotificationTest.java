package com.minicard.notification.domain;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationTest {

    private static final Instant NOW = Instant.parse("2026-06-14T00:00:00Z");

    @Test
    void createsImmutableIntentFromEvent() {
        UUID sourceEventId = UUID.randomUUID();
        Notification notification = Notification.requestFromEvent(
                sourceEventId,
                NotificationSubjectType.CARD_TRANSACTION,
                "txn-1",
                "card-123",
                NotificationType.CARD_TRANSACTION_POSTED,
                NOW
        );

        // 通知退回纯意图：只携带"发给谁、就哪个事实、发哪种通知"，投递状态在 NotificationDelivery。
        assertThat(notification.sourceEventId()).isEqualTo(sourceEventId);
        assertThat(notification.subjectType()).isEqualTo(NotificationSubjectType.CARD_TRANSACTION);
        assertThat(notification.subjectId()).isEqualTo("txn-1");
        assertThat(notification.recipientKey()).isEqualTo("card-123");
        assertThat(notification.type()).isEqualTo(NotificationType.CARD_TRANSACTION_POSTED);
        assertThat(notification.createdAt()).isEqualTo(NOW);
    }

    @Test
    void rejectsBlankRecipientKey() {
        assertThatThrownBy(() -> Notification.requestFromEvent(
                UUID.randomUUID(),
                NotificationSubjectType.AUTHORIZATION,
                "auth-1",
                "  ",
                NotificationType.AUTHORIZATION_APPROVED,
                NOW
        )).isInstanceOf(IllegalArgumentException.class);
    }
}
