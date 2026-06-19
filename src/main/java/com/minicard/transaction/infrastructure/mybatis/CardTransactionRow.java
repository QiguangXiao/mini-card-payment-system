package com.minicard.transaction.infrastructure.mybatis;

import java.math.BigDecimal;
import java.time.Instant;

public record CardTransactionRow(
        String id,
        String networkTransactionId,
        String authorizationId,
        String cardId,
        String creditAccountId,
        BigDecimal amount,
        String currency,
        String status,
        Instant presentmentReceivedAt,
        Instant postedAt,
        String statementId,
        Instant statementAssignedAt,
        Instant createdAt,
        Instant updatedAt
) {
}
