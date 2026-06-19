package com.minicard.statement.infrastructure.mybatis;

import java.math.BigDecimal;
import java.time.Instant;

public record StatementItemRow(
        String id,
        String statementId,
        String cardTransactionId,
        String networkTransactionId,
        String authorizationId,
        String cardId,
        BigDecimal amount,
        String currency,
        Instant postedAt,
        Instant createdAt
) {
}
