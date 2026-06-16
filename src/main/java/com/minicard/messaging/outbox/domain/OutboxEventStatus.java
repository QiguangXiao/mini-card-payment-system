package com.minicard.messaging.outbox.domain;

public enum OutboxEventStatus {
    PENDING,
    PROCESSING,
    PUBLISHED,
    DEAD
}
