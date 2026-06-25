package com.minicard.statement.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import lombok.Getter;
import lombok.experimental.Accessors;

/**
 * Statement batch 的一个分片任务。
 *
 * <p>关键词：账单分片任务, DB claim, PROCESSING lease,
 * statement job, FOR UPDATE SKIP LOCKED, 請求ジョブ(せいきゅうジョブ),
 * リース(リース)。</p>
 *
 * <p>Job 是 worker 并行处理的 durable work item。PENDING -> PROCESSING 是短事务 claim；
 * 真正生成 statement 在 worker 里按账户拆成小 transaction boundary。</p>
 */
@Getter
@Accessors(fluent = true)
public final class StatementJob {

    private final UUID id;
    private final UUID batchId;
    private final int shardNo;
    private final int shardCount;
    private StatementJobStatus status;
    private String claimedBy;
    private Instant claimedAt;
    private Instant claimUntil;
    private int attemptCount;
    private int processedAccountCount;
    private int generatedStatementCount;
    private int skippedAccountCount;
    private int failedAccountCount;
    private final Instant createdAt;
    private Instant updatedAt;
    private String lastError;

    private StatementJob(
            UUID id,
            UUID batchId,
            int shardNo,
            int shardCount,
            StatementJobStatus status,
            String claimedBy,
            Instant claimedAt,
            Instant claimUntil,
            int attemptCount,
            int processedAccountCount,
            int generatedStatementCount,
            int skippedAccountCount,
            int failedAccountCount,
            Instant createdAt,
            Instant updatedAt,
            String lastError
    ) {
        this.id = Objects.requireNonNull(id);
        this.batchId = Objects.requireNonNull(batchId);
        this.shardNo = shardNo;
        this.shardCount = shardCount;
        this.status = Objects.requireNonNull(status);
        this.claimedBy = claimedBy;
        this.claimedAt = claimedAt;
        this.claimUntil = claimUntil;
        this.attemptCount = attemptCount;
        this.processedAccountCount = processedAccountCount;
        this.generatedStatementCount = generatedStatementCount;
        this.skippedAccountCount = skippedAccountCount;
        this.failedAccountCount = failedAccountCount;
        this.createdAt = Objects.requireNonNull(createdAt);
        this.updatedAt = Objects.requireNonNull(updatedAt);
        this.lastError = lastError;
        validateState();
    }

    public static StatementJob pending(
            UUID batchId,
            int shardNo,
            int shardCount,
            Instant createdAt
    ) {
        // job id 在 domain factory 生成；batch_id + shard_no 唯一键负责 job creation idempotency。
        return new StatementJob(
                UUID.randomUUID(),
                batchId,
                shardNo,
                shardCount,
                StatementJobStatus.PENDING,
                null,
                null,
                null,
                0,
                0,
                0,
                0,
                0,
                createdAt,
                createdAt,
                null
        );
    }

    public static StatementJob restore(
            UUID id,
            UUID batchId,
            int shardNo,
            int shardCount,
            StatementJobStatus status,
            String claimedBy,
            Instant claimedAt,
            Instant claimUntil,
            int attemptCount,
            int processedAccountCount,
            int generatedStatementCount,
            int skippedAccountCount,
            int failedAccountCount,
            Instant createdAt,
            Instant updatedAt,
            String lastError
    ) {
        return new StatementJob(
                id,
                batchId,
                shardNo,
                shardCount,
                status,
                claimedBy,
                claimedAt,
                claimUntil,
                attemptCount,
                processedAccountCount,
                generatedStatementCount,
                skippedAccountCount,
                failedAccountCount,
                createdAt,
                updatedAt,
                lastError
        );
    }

    public void markProcessing(String workerId, Instant now, long leaseSeconds) {
        if (status != StatementJobStatus.PENDING) {
            throw new IllegalStateException("only PENDING statement job can be claimed");
        }
        status = StatementJobStatus.PROCESSING;
        claimedBy = requireText(workerId, "workerId");
        claimedAt = Objects.requireNonNull(now);
        claimUntil = now.plusSeconds(leaseSeconds);
        attemptCount++;
        updatedAt = now;
        lastError = null;
    }

    public void markDone(StatementJobExecutionResult result, Instant now) {
        requireProcessing();
        applyResult(result);
        status = StatementJobStatus.DONE;
        clearClaim();
        updatedAt = Objects.requireNonNull(now);
        lastError = null;
    }

    public void markFailed(
            StatementJobExecutionResult result,
            String error,
            Instant now,
            int maxAttempts
    ) {
        requireProcessing();
        if (result != null) {
            applyResult(result);
        }
        status = attemptCount >= maxAttempts ? StatementJobStatus.DEAD : StatementJobStatus.PENDING;
        clearClaim();
        updatedAt = Objects.requireNonNull(now);
        lastError = requireText(error, "error");
    }

    private void applyResult(StatementJobExecutionResult result) {
        processedAccountCount = result.processedAccountCount();
        generatedStatementCount = result.generatedStatementCount();
        skippedAccountCount = result.skippedAccountCount();
        failedAccountCount = result.failedAccountCount();
    }

    private void requireProcessing() {
        if (status != StatementJobStatus.PROCESSING) {
            throw new IllegalStateException("statement job is not PROCESSING");
        }
    }

    private void clearClaim() {
        claimedBy = null;
        claimedAt = null;
        claimUntil = null;
    }

    private void validateState() {
        if (shardCount <= 0 || shardNo < 0 || shardNo >= shardCount) {
            throw new IllegalArgumentException("statement job shard is invalid");
        }
        if (attemptCount < 0
                || processedAccountCount < 0
                || generatedStatementCount < 0
                || skippedAccountCount < 0
                || failedAccountCount < 0) {
            throw new IllegalArgumentException("statement job counters must be non-negative");
        }
        boolean hasClaim = claimedBy != null || claimedAt != null || claimUntil != null;
        if (status == StatementJobStatus.PROCESSING && !hasClaim) {
            throw new IllegalArgumentException("processing statement job requires claim lease");
        }
        if (status != StatementJobStatus.PROCESSING && hasClaim) {
            throw new IllegalArgumentException("non-processing statement job cannot keep claim lease");
        }
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
