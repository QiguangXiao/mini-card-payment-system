package com.minicard.messaging.inbox;

import java.time.Instant;
import java.util.UUID;

/**
 * Shared idempotency port for consumers that cannot store the source event id
 * directly in their business result.
 *
 * <p>Inbox is a messaging reliability pattern, not a business domain model, so
 * this port intentionally lives under {@code messaging.inbox} rather than a
 * bounded context's {@code domain} package.</p>
 */
public interface ConsumerInboxRepository {

    /**
     * Claims an event for one logical consumer using a database unique key.
     *
     * @return true for the first delivery; false for a duplicate delivery
     */
    boolean claim(String consumerName, UUID eventId, Instant processedAt);
}
