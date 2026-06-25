package com.minicard.statement.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

import lombok.Getter;
import lombok.experimental.Accessors;

/**
 * 一次 billing cycle 的 statement batch。
 *
 * <p>关键词：账单批次, 月度出账, 分片任务, statement batch,
 * billing cycle, sharded jobs, 請求バッチ(せいきゅうバッチ),
 * 締め処理(しめしょり)。</p>
 *
 * <p>Batch 是 durable audit record：scheduler 创建 batch/jobs 后，worker 可以并行处理；
 * 应用重启后也能从 statement_jobs 继续，而不是靠内存循环。</p>
 */
@Getter
@Accessors(fluent = true)
public final class StatementBatch {

    private final UUID id;
    private final LocalDate periodStart;
    private final LocalDate periodEnd;
    private final LocalDate dueDate;
    private StatementBatchStatus status;
    private final long totalAccountCount;
    private final int targetAccountsPerJob;
    private final int jobCount;
    private final Instant createdAt;
    private Instant completedAt;
    private String lastError;

    private StatementBatch(
            UUID id,
            LocalDate periodStart,
            LocalDate periodEnd,
            LocalDate dueDate,
            StatementBatchStatus status,
            long totalAccountCount,
            int targetAccountsPerJob,
            int jobCount,
            Instant createdAt,
            Instant completedAt,
            String lastError
    ) {
        this.id = Objects.requireNonNull(id);
        this.periodStart = Objects.requireNonNull(periodStart);
        this.periodEnd = Objects.requireNonNull(periodEnd);
        this.dueDate = Objects.requireNonNull(dueDate);
        this.status = Objects.requireNonNull(status);
        this.totalAccountCount = totalAccountCount;
        this.targetAccountsPerJob = targetAccountsPerJob;
        this.jobCount = jobCount;
        this.createdAt = Objects.requireNonNull(createdAt);
        this.completedAt = completedAt;
        this.lastError = lastError;
        validateState();
    }

    public static StatementBatch start(
            LocalDate periodStart,
            LocalDate periodEnd,
            LocalDate dueDate,
            long totalAccountCount,
            int targetAccountsPerJob,
            int jobCount,
            Instant createdAt
    ) {
        // batch id 在 domain factory 生成；cycle unique key 负责 idempotency。
        return new StatementBatch(
                UUID.randomUUID(),
                periodStart,
                periodEnd,
                dueDate,
                StatementBatchStatus.RUNNING,
                totalAccountCount,
                targetAccountsPerJob,
                jobCount,
                createdAt,
                null,
                null
        );
    }

    public static StatementBatch restore(
            UUID id,
            LocalDate periodStart,
            LocalDate periodEnd,
            LocalDate dueDate,
            StatementBatchStatus status,
            long totalAccountCount,
            int targetAccountsPerJob,
            int jobCount,
            Instant createdAt,
            Instant completedAt,
            String lastError
    ) {
        return new StatementBatch(
                id,
                periodStart,
                periodEnd,
                dueDate,
                status,
                totalAccountCount,
                targetAccountsPerJob,
                jobCount,
                createdAt,
                completedAt,
                lastError
        );
    }

    public void markCompleted(Instant completedAt) {
        if (status != StatementBatchStatus.RUNNING) {
            throw new IllegalStateException("only RUNNING batch can complete");
        }
        this.status = StatementBatchStatus.COMPLETED;
        this.completedAt = Objects.requireNonNull(completedAt);
        this.lastError = null;
    }

    public void markPartiallyFailed(String error, Instant completedAt) {
        if (status != StatementBatchStatus.RUNNING) {
            throw new IllegalStateException("only RUNNING batch can partially fail");
        }
        this.status = StatementBatchStatus.PARTIALLY_FAILED;
        this.completedAt = Objects.requireNonNull(completedAt);
        this.lastError = requireText(error, "error");
    }

    private void validateState() {
        if (periodEnd.isBefore(periodStart)) {
            throw new IllegalArgumentException("statement batch periodEnd must not be before periodStart");
        }
        if (!dueDate.isAfter(periodEnd)) {
            throw new IllegalArgumentException("statement batch dueDate must be after periodEnd");
        }
        if (totalAccountCount < 0 || targetAccountsPerJob <= 0 || jobCount < 0) {
            throw new IllegalArgumentException("statement batch counts are invalid");
        }
        if (status == StatementBatchStatus.RUNNING && completedAt != null) {
            throw new IllegalArgumentException("running batch cannot have completedAt");
        }
        if (status != StatementBatchStatus.RUNNING && completedAt == null) {
            throw new IllegalArgumentException("completed batch requires completedAt");
        }
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
