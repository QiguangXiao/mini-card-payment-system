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
 *
 * <p>关键词：延迟任务, PROCESSING lease, lease token, future business action,
 * retry, 遅延ジョブ(ちえんジョブ), リーストークン(リーストークン)。</p>
 *
 * <p>{@code nextAttemptAt} 是时间语义：PENDING 时代表下次可执行时间，PROCESSING 时代表 lease
 * deadline；{@code leaseToken} 是所有权语义：本轮 claim 的 owner identity。把这两个语义拆开，
 * worker finalize 时就不用比较 timestamp，从而避免 Java {@link Instant} 与 MySQL {@code TIMESTAMP(6)}
 * 精度差异造成误判。</p>
 */
// DelayJob 是状态机对象，只生成 getter，不生成 setter。
// 如果外部能直接 setStatus(DONE)，就会绕过 lease 校验、retry/backoff 和 lastError 记录。
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
    private String leaseToken;
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
            String leaseToken,
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
        this.leaseToken = leaseToken;
        this.createdAt = Objects.requireNonNull(createdAt);
        this.updatedAt = Objects.requireNonNull(updatedAt);
        this.lastError = lastError;
        validateLeaseState();
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
                null,
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
            String leaseToken,
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
                leaseToken,
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
        leaseToken = null;
    }

    public void markProcessing(Instant startedAt, long processingTimeoutSeconds, String leaseToken) {
        Instant actualStartedAt = Objects.requireNonNull(startedAt);
        if (processingTimeoutSeconds <= 0) {
            throw new IllegalArgumentException("processingTimeoutSeconds must be positive");
        }
        // PROCESSING 是 lease，不是永久状态。nextAttemptAt 是 deadline，leaseToken 是 owner identity。
        // 如果 finalize 只比较 nextAttemptAt，timestamp 精度差异或同 deadline 重领都可能让旧 worker 误覆盖新状态。
        status = DelayJobStatus.PROCESSING;
        updatedAt = actualStartedAt;
        nextAttemptAt = actualStartedAt.plusSeconds(processingTimeoutSeconds);
        this.leaseToken = requireText(leaseToken, "leaseToken");
    }

    public void markFailed(String error, Instant failedAt, int maxAttempts) {
        attempts++;
        updatedAt = Objects.requireNonNull(failedAt);
        // 先截断再写 DB，避免 last_error 太长导致“记录失败原因”本身失败。
        lastError = truncate(requireText(error, "error"));
        // 离开 PROCESSING 后释放本轮 token；下一轮 retry claim 必须产生新 token，旧 worker 不能再 finalize。
        leaseToken = null;
        if (attempts >= maxAttempts) {
            // DEAD 停止自动 retry，交给人工/运维判断是否需要 repair 或 replay。
            status = DelayJobStatus.DEAD;
            nextAttemptAt = failedAt;
            return;
        }

        status = DelayJobStatus.PENDING;
        // retry policy 与 Outbox 对称：短 exponential backoff，并设置上限避免可恢复任务睡太久。
        // 1L << n 表示 2^n；这里限制 n，避免 attempts 很大时位移溢出。
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

    private void validateLeaseState() {
        if (leaseToken != null && leaseToken.isBlank()) {
            throw new IllegalArgumentException("lease token must not be blank");
        }
        // PROCESSING 必须有 token；PENDING/DONE/DEAD 不能残留 token，避免旧 worker 把 stale lease 当成当前所有权。
        boolean hasLeaseToken = leaseToken != null;
        if (status == DelayJobStatus.PROCESSING && !hasLeaseToken) {
            throw new IllegalArgumentException("processing delay job requires lease token");
        }
        if (status != DelayJobStatus.PROCESSING && hasLeaseToken) {
            throw new IllegalArgumentException("non-processing delay job cannot keep lease token");
        }
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
