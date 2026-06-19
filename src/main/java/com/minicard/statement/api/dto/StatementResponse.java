package com.minicard.statement.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.minicard.statement.domain.Statement;

public record StatementResponse(
        UUID id,
        UUID creditAccountId,
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
        List<StatementItemResponse> items
) {

    public static StatementResponse from(Statement statement) {
        return new StatementResponse(
                statement.id(),
                statement.creditAccountId(),
                statement.periodStart(),
                statement.periodEnd(),
                statement.dueDate(),
                statement.totalAmount().amount(),
                statement.minimumPaymentAmount().amount(),
                statement.paidAmount().amount(),
                statement.totalAmount().currency().getCurrencyCode(),
                statement.transactionCount(),
                statement.status().name(),
                statement.generatedAt(),
                statement.items().stream()
                        .map(StatementItemResponse::from)
                        .toList()
        );
    }
}
