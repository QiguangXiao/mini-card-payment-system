package com.minicard.messaging.outbox.domain;

public enum OutboxEventStatus {
    PENDING,
    PUBLISHED,
    DEAD
}
