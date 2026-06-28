package com.minicard.statement.infrastructure.mybatis;

import java.time.Instant;
import java.time.LocalDate;

/**
 * statement_jobs 表的 MyBatis row DTO。
 */
public record StatementJobRow(
        String id,
        LocalDate periodStart,
        LocalDate periodEnd,
        LocalDate dueDate,
        int shardNo,
        int shardCount,
        String status,
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
}
