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
        /** delivery 主键；也会作为 provider idempotency key 使用。 */
        String id,
        /** 所属 notification intent id。 */
        String notificationId,
        /** NotificationChannel 字符串。 */
        String channel,
        /** NotificationType 字符串，作为渲染模板选择依据。 */
        String notificationType,
        /** 业务主题 id，例如 authorizationId/cardTransactionId/statementId。 */
        String subjectId,
        /** 收件人解析 key，当前项目暂用 cardId/creditAccountId 等业务线索。 */
        String recipientKey,
        /** NotificationDeliveryStatus 字符串。 */
        String status,
        /** 已失败尝试次数。 */
        int attempts,
        /** 下次可投递时间；PROCESSING 时表示 lease deadline。 */
        Instant nextAttemptAt,
        /** PROCESSING lease 的 owner token；非 PROCESSING 时为空。 */
        String leaseToken,
        /** 最近一次失败原因。 */
        String lastError,
        /** provider 成功回执 id。 */
        String providerMessageId,
        /** provider 确认发送成功时间。 */
        Instant sentAt,
        /** 创建时间。 */
        Instant createdAt,
        /** 更新时间。 */
        Instant updatedAt
) {
}
