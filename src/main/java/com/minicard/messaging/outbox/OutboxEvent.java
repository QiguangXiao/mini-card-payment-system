package com.minicard.messaging.outbox;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import lombok.Getter;
import lombok.experimental.Accessors;

/**
 * Kafka 发布前先落 MySQL 的 reliable-message state。
 *
 * <p>Outbox delivery 是 at-least-once：Kafka ack 后、MySQL commit 前宕机可能导致重复发布。
 * 所以后续 consumer 必须用 eventId 去重(deduplicate)。</p>
 *
 * <p>关键词：Outbox, PROCESSING lease, lease token, at-least-once,
 * idempotency, アウトボックス(アウトボックス), リーストークン(リーストークン)。</p>
 *
 * <p>{@code nextAttemptAt} 只表达 WHEN：PENDING 时是下次可发布时间，PROCESSING 时是 lease
 * deadline；{@code leaseToken} 表达 WHO：本轮 claim 的 owner identity。不能用
 * {@code nextAttemptAt} 兼任 token，否则 Java {@link Instant} 纳秒精度和 MySQL {@code TIMESTAMP(6)}
 * 微秒精度 round-trip 后可能不相等，迟到 worker 的 finalize 判断会变得不可靠。</p>
 */
// 这里只用 Lombok getter，不用 setter：Outbox 状态只能通过 markProcessing/markPublished/markFailed 转换。
// fluent getter 让 event.status() 贴近 record 风格，避免 JavaBean getter 噪音淹没状态机代码。
@Getter
@Accessors(fluent = true)
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
    private String leaseToken;
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
            String leaseToken,
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
        this.leaseToken = leaseToken;
        this.createdAt = Objects.requireNonNull(createdAt);
        this.publishedAt = publishedAt;
        this.lastError = lastError;
        validateLeaseState();
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
                null,
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
            String leaseToken,
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
                leaseToken,
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
        leaseToken = null;
    }

    public void markProcessing(Instant startedAt, long processingTimeoutSeconds, String leaseToken) {
        Instant actualStartedAt = Objects.requireNonNull(startedAt);
        if (processingTimeoutSeconds <= 0) {
            throw new IllegalArgumentException("processingTimeoutSeconds must be positive");
        }
        // PROCESSING 是短租约(lease)，不是最终状态。nextAttemptAt 是 deadline，leaseToken 是 owner identity。
        // 如果 finalize 只比较 nextAttemptAt，timestamp 精度差异或同 deadline 重领都可能让旧 worker 误覆盖新状态。
        status = OutboxEventStatus.PROCESSING;
        nextAttemptAt = actualStartedAt.plusSeconds(processingTimeoutSeconds);
        this.leaseToken = requireText(leaseToken, "leaseToken");
    }

    public void markFailed(String error, Instant failedAt, int maxAttempts) {
        attempts++;
        // lastError 长度先在 domain object 截断，避免 DB varchar 长度异常覆盖掉真正的发布失败原因。
        lastError = truncate(requireText(error, "error"));
        // 离开 PROCESSING 时释放本轮 lease token；下一次 claim 必须生成新 token，旧 worker 不能再 finalize。
        leaseToken = null;
        if (attempts >= maxAttempts) {
            // DEAD event 停止自动 retry，避免 poison message 无限重试并掩盖真实故障。
            status = OutboxEventStatus.DEAD;
            nextAttemptAt = failedAt;
            return;
        }

        // exponential backoff 降低 Kafka outage 时的压力；上限避免恢复后事件等待太久。
        // 1L << n 是 Java 位移写法，等价于 2^n；这里用 Math.min 限制指数，避免 attempts 很大时溢出。
        long delaySeconds = Math.min(
                1L << Math.min(attempts - 1, 6),
                MAX_RETRY_DELAY_SECONDS
        );
        status = OutboxEventStatus.PENDING;
        nextAttemptAt = failedAt.plusSeconds(delaySeconds);
    }

    public void markProcessingTimedOut(Instant recoveredAt, int maxAttempts) {
        // PROCESSING lease 超时通常表示 publisher worker 宕机或卡住。
        // 恢复路径按一次失败处理，让 retry/backoff/DEAD 状态机统一承接故障。
        markFailed("outbox processing lease expired", recoveredAt, maxAttempts);
    }

    private String truncate(String value) {
        return value.length() <= MAX_ERROR_LENGTH
                ? value
                : value.substring(0, MAX_ERROR_LENGTH);
    }

    private void validateLeaseState() {
        if (leaseToken != null && leaseToken.isBlank()) {
            throw new IllegalArgumentException("lease token must not be blank");
        }
        // PROCESSING 必须带 token；非 PROCESSING 不能残留 token。否则迟到 worker/recoverer 会读到脏 ownership。
        boolean hasLeaseToken = leaseToken != null;
        if (status == OutboxEventStatus.PROCESSING && !hasLeaseToken) {
            throw new IllegalArgumentException("processing outbox event requires lease token");
        }
        if (status != OutboxEventStatus.PROCESSING && hasLeaseToken) {
            throw new IllegalArgumentException("non-processing outbox event cannot keep lease token");
        }
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
