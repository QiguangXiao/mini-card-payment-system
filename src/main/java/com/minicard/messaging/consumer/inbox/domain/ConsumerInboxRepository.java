package com.minicard.messaging.consumer.inbox.domain;

import java.time.Instant;
import java.util.UUID;

public interface ConsumerInboxRepository {

    /**
     * Claims an event for one logical consumer using a database unique key.
     *
     * @return true for the first delivery; false for a duplicate delivery
     */
    boolean claim(String consumerName, UUID eventId, Instant processedAt);
}
