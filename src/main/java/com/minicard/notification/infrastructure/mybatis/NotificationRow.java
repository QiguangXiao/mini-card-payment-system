package com.minicard.notification.infrastructure.mybatis;

import java.time.Instant;

/**
 * notifications 表的 MyBatis row DTO。
 *
 * <p>关键词：通知行, 通知主题, 收件人, notification row,
 * subject, recipient key, 通知行(つうちぎょう),
 * 宛先(あてさき)。</p>
 *
 * <p>Database shape 与 aggregate 分开，避免 MyBatis 的 String id 转换泄漏到 domain model。</p>
 */
public record NotificationRow(
        /** notification id。 */
        String id,
        /** 来源 integration event id，用于幂等。 */
        String sourceEventId,
        /** 通知主题类型。 */
        String subjectType,
        /** 通知主题 id。 */
        String subjectId,
        /** 收件人 key。 */
        String recipientKey,
        /** 通知模板/类型。 */
        String template,
        /** NotificationStatus 字符串。 */
        String status,
        /** 已投递尝试次数。 */
        int deliveryAttempts,
        /** 最近失败原因。 */
        String lastError,
        /** 成功投递时间。 */
        Instant sentAt,
        /** 创建时间。 */
        Instant createdAt,
        /** 更新时间。 */
        Instant updatedAt
) {
}
