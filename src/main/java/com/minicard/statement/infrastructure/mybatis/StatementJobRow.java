package com.minicard.statement.infrastructure.mybatis;

import java.time.Instant;

/**
 * statement_jobs 表的 MyBatis row DTO。
 */
public record StatementJobRow(
        String id,
        String batchId,
        int shardNo,
        int shardCount,
        String status,
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
}
