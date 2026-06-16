package com.minicard.messaging.outbox.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Kafka 发布前先落 MySQL 的 reliable-message state。
 *
 * <p>Outbox delivery 是 at-least-once：Kafka ack 后、MySQL commit 前宕机可能导致重复发布。
 * 所以后续 consumer 必须用 eventId 去重(deduplicate)。</p>
 */
public final class OutboxEvent {

    private static final int MAX_ERROR_LENGTH = 500;
    private static final long MAX_RETRY_DELAY_SECONDS = 60;

    private final UUID id;
    private final String aggregateType;
    private final String aggregateId;
    private final String eventType;
    private final int eventVersion;
    private final String partitionKey;
    private final String payload;
    private OutboxEventStatus status;
    private int attempts;
    private Instant nextAttemptAt;
    private final Instant createdAt;
    private Instant publishedAt;
    private String lastError;

    private OutboxEvent(
            UUID id,
            String aggregateType,
            String aggregateId,
            String eventType,
            int eventVersion,
            String partitionKey,
            String payload,
            OutboxEventStatus status,
            int attempts,
            Instant nextAttemptAt,
            Instant createdAt,
            Instant publishedAt,
            String lastError
    ) {
        this.id = Objects.requireNonNull(id);
        this.aggregateType = requireText(aggregateType, "aggregateType");
        this.aggregateId = requireText(aggregateId, "aggregateId");
        this.eventType = requireText(eventType, "eventType");
        if (eventVersion < 1) {
            throw new IllegalArgumentException("eventVersion must be positive");
        }
        this.eventVersion = eventVersion;
        this.partitionKey = requireText(partitionKey, "partitionKey");
        this.payload = requireText(payload, "payload");
        this.status = Objects.requireNonNull(status);
        if (attempts < 0) {
            throw new IllegalArgumentException("attempts must not be negative");
        }
        this.attempts = attempts;
        this.nextAttemptAt = Objects.requireNonNull(nextAttemptAt);
        this.createdAt = Objects.requireNonNull(createdAt);
        this.publishedAt = publishedAt;
        this.lastError = lastError;
    }

    public static OutboxEvent pending(
            UUID id,
            String aggregateType,
            String aggregateId,
            String eventType,
            int eventVersion,
            String partitionKey,
            String payload,
            Instant createdAt
    ) {
        // pending() 创建尚未发布的事件；eventId 由业务 adapter 生成，payload 已经序列化好。
        return new OutboxEvent(
                id,
                aggregateType,
                aggregateId,
                eventType,
                eventVersion,
                partitionKey,
                payload,
                OutboxEventStatus.PENDING,
                0,
                createdAt,
                createdAt,
                null,
                null
        );
    }

    public static OutboxEvent restore(
            UUID id,
            String aggregateType,
            String aggregateId,
            String eventType,
            int eventVersion,
            String partitionKey,
            String payload,
            OutboxEventStatus status,
            int attempts,
            Instant nextAttemptAt,
            Instant createdAt,
            Instant publishedAt,
            String lastError
    ) {
        return new OutboxEvent(
                id,
                aggregateType,
                aggregateId,
                eventType,
                eventVersion,
                partitionKey,
                payload,
                status,
                attempts,
                nextAttemptAt,
                createdAt,
                publishedAt,
                lastError
        );
    }

    public void markPublished(Instant publishedAt) {
        // PUBLISHED 表示 broker 已确认，Outbox scheduler 不再自动重发。
        status = OutboxEventStatus.PUBLISHED;
        this.publishedAt = Objects.requireNonNull(publishedAt);
        lastError = null;
    }

    public void markProcessing(Instant startedAt, long processingTimeoutSeconds) {
        Instant actualStartedAt = Objects.requireNonNull(startedAt);
        if (processingTimeoutSeconds <= 0) {
            throw new IllegalArgumentException("processingTimeoutSeconds must be positive");
        }
        // PROCESSING 是短租约(lease)，不是最终状态。
        // publisher 宕机时，nextAttemptAt 到期后其他实例可以重新 claim 并重发。
        status = OutboxEventStatus.PROCESSING;
        nextAttemptAt = actualStartedAt.plusSeconds(processingTimeoutSeconds);
    }

    public void markFailed(String error, Instant failedAt, int maxAttempts) {
        attempts++;
        lastError = truncate(requireText(error, "error"));
        if (attempts >= maxAttempts) {
            // DEAD event 停止自动 retry，避免 poison message 无限重试并掩盖真实故障。
            status = OutboxEventStatus.DEAD;
            nextAttemptAt = failedAt;
            return;
        }

        // exponential backoff 降低 Kafka outage 时的压力；上限避免恢复后事件等待太久。
        long delaySeconds = Math.min(
                1L << Math.min(attempts - 1, 6),
                MAX_RETRY_DELAY_SECONDS
        );
        status = OutboxEventStatus.PENDING;
        nextAttemptAt = failedAt.plusSeconds(delaySeconds);
    }

    private String truncate(String value) {
        return value.length() <= MAX_ERROR_LENGTH
                ? value
                : value.substring(0, MAX_ERROR_LENGTH);
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }

    public UUID id() {
        return id;
    }

    public String aggregateType() {
        return aggregateType;
    }

    public String aggregateId() {
        return aggregateId;
    }

    public String eventType() {
        return eventType;
    }

    public int eventVersion() {
        return eventVersion;
    }

    public String partitionKey() {
        return partitionKey;
    }

    public String payload() {
        return payload;
    }

    public OutboxEventStatus status() {
        return status;
    }

    public int attempts() {
        return attempts;
    }

    public Instant nextAttemptAt() {
        return nextAttemptAt;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant publishedAt() {
        return publishedAt;
    }

    public String lastError() {
        return lastError;
    }
}
