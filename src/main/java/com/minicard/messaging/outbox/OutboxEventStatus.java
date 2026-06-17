package com.minicard.messaging.outbox;

public enum OutboxEventStatus {
    PENDING,
    PROCESSING,
    PUBLISHED,
    DEAD
}
