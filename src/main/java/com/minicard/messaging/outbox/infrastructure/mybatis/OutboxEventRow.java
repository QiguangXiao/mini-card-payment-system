package com.minicard.messaging.outbox.infrastructure.mybatis;

import java.time.Instant;

public record OutboxEventRow(
        String id,
        String aggregateType,
        String aggregateId,
        String eventType,
        Integer eventVersion,
        String partitionKey,
        String payload,
        String status,
        Integer attempts,
        Instant nextAttemptAt,
        Instant createdAt,
        Instant publishedAt,
        String lastError
) {
}
