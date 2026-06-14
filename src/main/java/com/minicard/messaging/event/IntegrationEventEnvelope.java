package com.minicard.messaging.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Stable metadata shared by every integration event.
 *
 * <p>Consumers use {@code eventId} for idempotency and {@code eventVersion} for
 * schema evolution. The payload can evolve independently from the internal
 * Authorization aggregate.</p>
 */
public record IntegrationEventEnvelope<T>(
        UUID eventId,
        String eventType,
        int eventVersion,
        Instant occurredAt,
        T payload
) {
}
