package com.minicard.notification.infrastructure.mybatis;

import java.time.Instant;

/**
 * Database shape kept separate from the aggregate so MyBatis-specific String
 * identifier conversion does not leak into the domain model.
 */
public record NotificationRow(
        String id,
        String sourceEventId,
        String subjectType,
        String subjectId,
        String recipientKey,
        String template,
        String status,
        int deliveryAttempts,
        String lastError,
        Instant sentAt,
        Instant createdAt,
        Instant updatedAt
) {
}
