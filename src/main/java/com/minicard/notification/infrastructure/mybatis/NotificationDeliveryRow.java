package com.minicard.notification.infrastructure.mybatis;

import java.time.Instant;

/**
 * notification_deliveries 表的 MyBatis row DTO。
 *
 * <p>关键词：投递行, 渠道, 处理租约, delivery row, channel, processing lease,
 * 配信行(はいしんぎょう)。</p>
 *
 * <p>Database shape 与 aggregate 分开：channel/status/notificationType 在库里都是字符串，
 * 不让这些字符串转换泄漏进 NotificationDelivery domain model。</p>
 */
public record NotificationDeliveryRow(
        String id,
        String notificationId,
        String channel,
        String notificationType,
        String subjectId,
        String recipientKey,
        String status,
        int attempts,
        Instant nextAttemptAt,
        String lastError,
        String providerMessageId,
        Instant sentAt,
        Instant createdAt,
        Instant updatedAt
) {
}
