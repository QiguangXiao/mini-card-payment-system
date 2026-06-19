package com.minicard.notification.application;

import java.util.UUID;

import com.minicard.notification.domain.NotificationType;

/**
 * Transport-neutral input for requesting an authorization notification.
 *
 * <p>Kafka listener 只负责把 eventType 翻译成 NotificationType；
 * application service 不关心消息来自 Kafka、重放脚本还是未来 admin endpoint。</p>
 */
public record RequestAuthorizationNotificationCommand(
        UUID sourceEventId,
        UUID authorizationId,
        String cardId,
        NotificationType type
) {
}
