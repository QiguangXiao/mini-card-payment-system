package com.minicard.statement.infrastructure.mybatis;

import java.time.Instant;
import java.time.LocalDate;

/**
 * statement_batches 表的 MyBatis row DTO。
 */
public record StatementBatchRow(
        String id,
        LocalDate periodStart,
        LocalDate periodEnd,
        LocalDate dueDate,
        String status,
        long totalAccountCount,
        int targetAccountsPerJob,
        int jobCount,
        Instant createdAt,
        Instant completedAt,
        String lastError
) {
}
