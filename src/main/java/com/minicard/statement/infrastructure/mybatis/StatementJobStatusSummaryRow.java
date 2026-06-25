package com.minicard.statement.infrastructure.mybatis;

/**
 * statement_jobs 状态聚合 SQL row。
 */
public record StatementJobStatusSummaryRow(
        int totalCount,
        int pendingCount,
        int processingCount,
        int doneCount,
        int deadCount
) {
}
