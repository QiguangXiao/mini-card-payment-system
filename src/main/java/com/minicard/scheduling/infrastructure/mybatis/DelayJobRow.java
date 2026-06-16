package com.minicard.scheduling.infrastructure.mybatis;

import java.time.Instant;

public record DelayJobRow(
        String id,
        String jobType,
        String aggregateType,
        String aggregateId,
        String status,
        int attempts,
        Instant scheduledAt,
        Instant nextAttemptAt,
        Instant createdAt,
        Instant updatedAt,
        String lastError
) {
}
