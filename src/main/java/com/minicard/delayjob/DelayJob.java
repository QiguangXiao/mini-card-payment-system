package com.minicard.delayjob;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import lombok.Getter;
import lombok.experimental.Accessors;

/**
 * 延迟业务动作的 durable plan，当前用于“7 天未 capture 的授权自动过期”。
 *
 * <p>它刻意和 OutboxEvent 保持相似：都是 database-backed work queue，都由 scheduler 轮询。
 * 区别是 Outbox 负责 publish message，DelayJob 负责 execute future business action。</p>
 */
@Getter
@Accessors(fluent = true)
public final class DelayJob {

    private static final int MAX_ERROR_LENGTH = 500;
    private static final long MAX_RETRY_DELAY_SECONDS = 60;

    private final UUID id;
    private final DelayJobType jobType;
    private final String aggregateType;
    private final String aggregateId;
    private DelayJobStatus status;
    private int attempts;
    private final Instant scheduledAt;
    private Instant nextAttemptAt;
    private final Instant createdAt;
    private Instant updatedAt;
    private String lastError;

    private DelayJob(
            UUID id,
            DelayJobType jobType,
            String aggregateType,
            String aggregateId,
            DelayJobStatus status,
            int attempts,
            Instant scheduledAt,
            Instant nextAttemptAt,
            Instant createdAt,
            Instant updatedAt,
            String lastError
    ) {
        this.id = Objects.requireNonNull(id);
        this.jobType = Objects.requireNonNull(jobType);
        this.aggregateType = requireText(aggregateType, "aggregateType");
        this.aggregateId = requireText(aggregateId, "aggregateId");
        this.status = Objects.requireNonNull(status);
        if (attempts < 0) {
            throw new IllegalArgumentException("attempts must not be negative");
        }
        this.attempts = attempts;
        this.scheduledAt = Objects.requireNonNull(scheduledAt);
        this.nextAttemptAt = Objects.requireNonNull(nextAttemptAt);
        this.createdAt = Objects.requireNonNull(createdAt);
        this.updatedAt = Objects.requireNonNull(updatedAt);
        this.lastError = lastError;
    }

    public static DelayJob pending(
            UUID id,
            DelayJobType jobType,
            String aggregateType,
            String aggregateId,
            Instant scheduledAt,
            Instant createdAt
    ) {
        // pending() 是新 job 的 factory method；job id 由调用方生成，scheduledAt 决定最早执行时间。
        return new DelayJob(
                id,
                jobType,
                aggregateType,
                aggregateId,
                DelayJobStatus.PENDING,
                0,
                scheduledAt,
                scheduledAt,
                createdAt,
                createdAt,
                null
        );
    }

    public static DelayJob restore(
            UUID id,
            DelayJobType jobType,
            String aggregateType,
            String aggregateId,
            DelayJobStatus status,
            int attempts,
            Instant scheduledAt,
            Instant nextAttemptAt,
            Instant createdAt,
            Instant updatedAt,
            String lastError
    ) {
        return new DelayJob(
                id,
                jobType,
                aggregateType,
                aggregateId,
                status,
                attempts,
                scheduledAt,
                nextAttemptAt,
                createdAt,
                updatedAt,
                lastError
        );
    }

    public void markDone(Instant doneAt) {
        // DONE 表示业务动作已成功执行，scheduler 不会再次自动处理。
        status = DelayJobStatus.DONE;
        updatedAt = Objects.requireNonNull(doneAt);
        lastError = null;
    }

    public void markProcessing(Instant startedAt, long processingTimeoutSeconds) {
        Instant actualStartedAt = Objects.requireNonNull(startedAt);
        if (processingTimeoutSeconds <= 0) {
            throw new IllegalArgumentException("processingTimeoutSeconds must be positive");
        }
        // PROCESSING 是 lease，不是永久状态。pod claim 后宕机时，
        // nextAttemptAt 到期后其他 pod 可以 reclaim，避免 job 永久卡死。
        status = DelayJobStatus.PROCESSING;
        updatedAt = actualStartedAt;
        nextAttemptAt = actualStartedAt.plusSeconds(processingTimeoutSeconds);
    }

    public void markFailed(String error, Instant failedAt, int maxAttempts) {
        attempts++;
        updatedAt = Objects.requireNonNull(failedAt);
        lastError = truncate(requireText(error, "error"));
        if (attempts >= maxAttempts) {
            // DEAD 停止自动 retry，交给人工/运维判断是否需要 repair 或 replay。
            status = DelayJobStatus.DEAD;
            nextAttemptAt = failedAt;
            return;
        }

        status = DelayJobStatus.PENDING;
        // retry policy 与 Outbox 对称：短 exponential backoff，并设置上限避免可恢复任务睡太久。
        long delaySeconds = Math.min(
                1L << Math.min(attempts - 1, 6),
                MAX_RETRY_DELAY_SECONDS
        );
        nextAttemptAt = failedAt.plusSeconds(delaySeconds);
    }

    public void markProcessingTimedOut(Instant recoveredAt, int maxAttempts) {
        // PROCESSING lease 超时说明 worker 可能宕机或卡住。
        // 这里把它当作一次失败记录，交回 PENDING/DEAD 状态机，避免长期占用任务。
        markFailed("processing lease expired", recoveredAt, maxAttempts);
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
}
