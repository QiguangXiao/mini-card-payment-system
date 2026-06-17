package com.minicard.authorization.infrastructure.messaging;

import java.time.Instant;
import java.util.UUID;

/**
 * AuthorizationMessageMapper 的输出。
 *
 * <p>它是写 Outbox 前的中间模型：包含 message metadata 和 payload，
 * 但还没有绑定到 MySQL outbox row 或 Kafka producer。</p>
 */
record AuthorizationMessage(
        UUID eventId,
        UUID aggregateId,
        String eventType,
        int eventVersion,
        Instant occurredAt,
        String partitionKey,
        Object payload
) {
}
