package com.minicard.statement.infrastructure.mybatis;

import java.time.Instant;
import java.time.LocalDate;

/**
 * statement_jobs 表的 MyBatis row DTO。
 *
 * <p>Row 只表达数据库字段；claim/lease/retry 规则由 StatementJob domain model 保护。</p>
 */
public record StatementJobRow(
        /** statement job 主键。 */
        String id,
        /** 出账周期开始日。 */
        LocalDate periodStart,
        /** 出账周期结束日。 */
        LocalDate periodEnd,
        /** 本周期账单统一到期日。 */
        LocalDate dueDate,
        /** 当前分片编号。 */
        int shardNo,
        /** 总分片数。 */
        int shardCount,
        /** StatementJobStatus 字符串。 */
        String status,
        /** 当前领取 job 的 worker id，仅用于观测，不单独代表 ownership。 */
        String claimedBy,
        /** 本轮 claim 时间。 */
        Instant claimedAt,
        /** lease 截止时间；超时后 dispatcher/recoverer 可重新放回 PENDING。 */
        Instant claimUntil,
        /** 本轮 claim 的 owner token，finalize 必须校验。 */
        String claimToken,
        /** 已尝试次数。 */
        int attemptCount,
        /** 已扫描账户数。 */
        int processedAccountCount,
        /** 成功生成账单数。 */
        int generatedStatementCount,
        /** 跳过账户数。 */
        int skippedAccountCount,
        /** 失败账户数。 */
        int failedAccountCount,
        /** 创建时间。 */
        Instant createdAt,
        /** 更新时间。 */
        Instant updatedAt,
        /** 最近一次失败原因。 */
        String lastError
) {
}
