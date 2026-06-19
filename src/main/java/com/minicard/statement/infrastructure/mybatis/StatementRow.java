package com.minicard.statement.infrastructure.mybatis;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record StatementRow(
        String id,
        String creditAccountId,
        LocalDate periodStart,
        LocalDate periodEnd,
        LocalDate dueDate,
        BigDecimal totalAmount,
        BigDecimal minimumPaymentAmount,
        BigDecimal paidAmount,
        String currency,
        int transactionCount,
        String status,
        Instant generatedAt,
        Instant createdAt,
        Instant updatedAt
) {
}
