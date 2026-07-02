package com.minicard.statement.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

import lombok.Getter;
import lombok.experimental.Accessors;

/**
 * 一个 billing cycle 的分片任务（sharded claimable job）。
 *
 * <p>关键词：账单分片任务, DB claim, PROCESSING lease, claimable job,
 * statement job, FOR UPDATE SKIP LOCKED, 請求ジョブ(せいきゅうジョブ),
 * リース(リース)。</p>
 *
 * <p>这是本项目“claimable job”的 reference shape：状态机
 * PENDING → PROCESSING(lease) → DONE / 重试回 PENDING / DEAD。
 * 它自带 cycle 身份(period/dueDate)和 shard 信息，不再依赖一个 parent batch 行——
 * “整个周期是否出账完成”用 statement_jobs 上的查询回答即可，避免额外的 batch 生命周期表。
 * 其他 domain 的 job 之后可以对齐成同样的 claim/lease/recover 形状。</p>
 *
 * <p>job id 在 domain factory 生成；(period_start, period_end, shard_no) 唯一键
 * 负责 job creation idempotency：scheduler 重复触发同一个 close date 不会产生重复分片。</p>
 */
// StatementJob 是状态机对象，只生成 getter 不生成 setter。
// 如果外部能直接 setStatus(DONE)，就会绕过 lease 校验、attempt 计数和 lastError 记录。
@Getter
@Accessors(fluent = true)
public final class StatementJob {

    /** StatementJob 主键；每个 billing cycle shard 一条 claimable job。 */
    private final UUID id;
    /** cycle 身份直接落在 job 上：worker 处理分片时不需要再去读 parent batch 行。 */
    private final LocalDate periodStart;
    /** 本次出账周期结束日，和 periodStart/shardNo 共同参与唯一性语义。 */
    private final LocalDate periodEnd;
    /** 本批生成账单的统一还款到期日。 */
    private final LocalDate dueDate;
    /** 当前分片编号；按账户 id hash/mod 分配要处理的账户集合。 */
    private final int shardNo;
    /** 总分片数；和 shardNo 一起定义本 job 的处理范围。 */
    private final int shardCount;
    /** job 状态机：PENDING、PROCESSING、DONE、DEAD。 */
    private StatementJobStatus status;
    /** 当前领取 job 的 worker id；用于日志排查，不作为唯一 ownership 判断。 */
    private String claimedBy;
    /** 本轮 claim 发生时间。 */
    private Instant claimedAt;
    /** claim_until 只表达 lease 截止时间；真正的 owner identity 由 claim_token 承担。 */
    private Instant claimUntil;
    /** 每次 claim 生成的随机 token；finalize 只认这个 token，避免用 timestamp 当 ownership。 */
    private String claimToken;
    /** 已领取/尝试次数；每次 claim 自增，达到上限后失败会进入 DEAD。 */
    private int attemptCount;
    /** 本轮实际扫描的账户数，用于观测 job 是否覆盖了预期分片。 */
    private int processedAccountCount;
    /** 成功生成账单的账户数。 */
    private int generatedStatementCount;
    /** 因无可出账交易、已存在账单等原因跳过的账户数。 */
    private int skippedAccountCount;
    /** 本轮处理失败的账户数；非 0 时通常需要查看 lastError 和日志。 */
    private int failedAccountCount;
    /** job 创建时间。 */
    private final Instant createdAt;
    /** 最近一次状态变化时间。 */
    private Instant updatedAt;
    /** 最近一次失败原因；进入 DEAD 后用于人工 repair/replay 判断。 */
    private String lastError;

    private StatementJob(
            UUID id,
            LocalDate periodStart,
            LocalDate periodEnd,
            LocalDate dueDate,
            int shardNo,
            int shardCount,
            StatementJobStatus status,
            String claimedBy,
            Instant claimedAt,
            Instant claimUntil,
            String claimToken,
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
        this.periodStart = Objects.requireNonNull(periodStart);
        this.periodEnd = Objects.requireNonNull(periodEnd);
        this.dueDate = Objects.requireNonNull(dueDate);
        this.shardNo = shardNo;
        this.shardCount = shardCount;
        this.status = Objects.requireNonNull(status);
        this.claimedBy = claimedBy;
        this.claimedAt = claimedAt;
        this.claimUntil = claimUntil;
        this.claimToken = claimToken;
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
            LocalDate periodStart,
            LocalDate periodEnd,
            LocalDate dueDate,
            int shardNo,
            int shardCount,
            Instant createdAt
    ) {
        return new StatementJob(
                UUID.randomUUID(),
                periodStart,
                periodEnd,
                dueDate,
                shardNo,
                shardCount,
                StatementJobStatus.PENDING,
                null,
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
            LocalDate periodStart,
            LocalDate periodEnd,
            LocalDate dueDate,
            int shardNo,
            int shardCount,
            StatementJobStatus status,
            String claimedBy,
            Instant claimedAt,
            Instant claimUntil,
            String claimToken,
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
                periodStart,
                periodEnd,
                dueDate,
                shardNo,
                shardCount,
                status,
                claimedBy,
                claimedAt,
                claimUntil,
                claimToken,
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

    /**
     * 领取一个 PENDING 分片任务并写入 PROCESSING lease，供 worker pool 异步执行。
     */
    public void markProcessing(String workerId, Instant now, long leaseSeconds) {
        // 只有 PENDING 能被 claim。claim 在短事务内提交，commit 后才把 job 交给 worker pool 处理。
        if (status != StatementJobStatus.PENDING) {
            throw new IllegalStateException("only PENDING statement job can be claimed");
        }
        status = StatementJobStatus.PROCESSING;
        claimedBy = requireText(workerId, "workerId");
        claimedAt = Objects.requireNonNull(now);
        claimUntil = now.plusSeconds(leaseSeconds);
        // 每次 claim 都生成新的随机 owner token。只比较 claim_until 会受 DB timestamp 精度影响；
        // 只比较 claimed_by 又挡不住同一个 worker 过期后重新 claim 的情况。
        claimToken = UUID.randomUUID().toString();
        attemptCount++;
        updatedAt = now;
        lastError = null;
    }

    /**
     * 在分片出账成功后标记 DONE，并保存本轮处理统计。
     */
    public void markDone(StatementJobExecutionResult result, Instant now) {
        requireProcessing();
        applyResult(result);
        status = StatementJobStatus.DONE;
        clearClaim();
        updatedAt = Objects.requireNonNull(now);
        lastError = null;
    }

    /**
     * 在分片处理失败后保存统计和错误，并按 attemptCount 决定重试或 DEAD。
     */
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
        // attemptCount 在 claim 时已自增；达到上限就进入 DEAD，等待人工排查，否则回到 PENDING 等待重试。
        status = attemptCount >= maxAttempts ? StatementJobStatus.DEAD : StatementJobStatus.PENDING;
        clearClaim();
        updatedAt = Objects.requireNonNull(now);
        lastError = requireText(error, "error");
    }

    /**
     * 把 handler 返回的处理统计复制到 job，方便之后按 job row 观测批处理结果。
     */
    private void applyResult(StatementJobExecutionResult result) {
        processedAccountCount = result.processedAccountCount();
        generatedStatementCount = result.generatedStatementCount();
        skippedAccountCount = result.skippedAccountCount();
        failedAccountCount = result.failedAccountCount();
    }

    /**
     * 保护 DONE/FAILED 只能从 PROCESSING lease finalize，避免未领取 job 被直接结束。
     */
    private void requireProcessing() {
        if (status != StatementJobStatus.PROCESSING) {
            throw new IllegalStateException("statement job is not PROCESSING");
        }
    }

    /**
     * 释放本轮 claim 信息，使 PENDING/DONE/DEAD 不再携带过期 ownership。
     */
    private void clearClaim() {
        claimedBy = null;
        claimedAt = null;
        claimUntil = null;
        claimToken = null;
    }

    /**
     * 校验分片、计数器和 lease 字段组合，防止 restore 出 partial claim。
     */
    private void validateState() {
        if (periodEnd.isBefore(periodStart) || !dueDate.isAfter(periodEnd)) {
            throw new IllegalArgumentException("statement job cycle dates are invalid");
        }
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
        if (claimToken != null && claimToken.isBlank()) {
            throw new IllegalArgumentException("claim token must not be blank");
        }
        // PROCESSING 必须持有完整 lease；非 PROCESSING 不能残留 lease。
        // 如果允许 partial claim（例如只有 claim_until、没有 claim_token），recover 还能扫到，
        // 但迟到 worker finalize 时就无法可靠判断这次 PROCESSING 是否仍属于自己。
        boolean hasAnyClaim = claimedBy != null || claimedAt != null || claimUntil != null || claimToken != null;
        boolean hasFullClaim = claimedBy != null && claimedAt != null && claimUntil != null && claimToken != null;
        if (status == StatementJobStatus.PROCESSING && !hasFullClaim) {
            throw new IllegalArgumentException("processing statement job requires claim lease");
        }
        if (status != StatementJobStatus.PROCESSING && hasAnyClaim) {
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
