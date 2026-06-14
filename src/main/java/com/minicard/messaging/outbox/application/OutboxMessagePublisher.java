package com.minicard.messaging.outbox.application;

import java.time.Duration;

import com.minicard.messaging.outbox.domain.OutboxEvent;

/**
 * Output port implemented by Kafka infrastructure.
 */
public interface OutboxMessagePublisher {

    void publish(OutboxEvent event, Duration timeout);
}
