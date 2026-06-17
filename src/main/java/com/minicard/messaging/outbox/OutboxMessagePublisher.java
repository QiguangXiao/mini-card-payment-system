package com.minicard.messaging.outbox;

import java.time.Duration;


/**
 * Output port implemented by Kafka infrastructure.
 */
public interface OutboxMessagePublisher {

    void publish(OutboxEvent event, Duration timeout);
}
