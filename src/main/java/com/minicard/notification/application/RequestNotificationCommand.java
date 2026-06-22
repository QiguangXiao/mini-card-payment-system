package com.minicard.notification.application;

import java.util.UUID;

import com.minicard.notification.domain.NotificationSubjectType;
import com.minicard.notification.domain.NotificationType;

/**
 * Transport-neutral input for requesting a customer notification.
 *
 * <p>Kafka listener 只负责把 eventType/payload 翻译成这个 command；
 * application service 不关心消息来自 Kafka、重放脚本还是未来 admin endpoint。</p>
 */
// command record 固定 listener 到 application 的输入 shape。
// 如果 listener 直接传散参数，sourceEventId/type/subjectId 很容易在多个 listener 中顺序错位。
public record RequestNotificationCommand(
        UUID sourceEventId,
        NotificationSubjectType subjectType,
        String subjectId,
        String recipientKey,
        NotificationType type
) {
}
