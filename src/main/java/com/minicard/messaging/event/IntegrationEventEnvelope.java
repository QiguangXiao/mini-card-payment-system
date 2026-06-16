package com.minicard.messaging.event;

import java.time.Instant;
import java.util.UUID;

/**
 * 所有 integration event 共享的稳定 metadata 外壳。
 *
 * <p>Consumer 用 {@code eventId} 做 idempotency，用 {@code eventVersion} 做 schema evolution。
 * payload 可以独立于内部 Authorization aggregate 演进。</p>
 */
public record IntegrationEventEnvelope<T>(
        UUID eventId,
        String eventType,
        int eventVersion,
        Instant occurredAt,
        T payload
) {
}
