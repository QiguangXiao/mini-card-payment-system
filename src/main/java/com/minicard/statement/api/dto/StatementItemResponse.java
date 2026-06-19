package com.minicard.statement.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.minicard.statement.domain.StatementItem;

public record StatementItemResponse(
        UUID id,
        UUID cardTransactionId,
        String networkTransactionId,
        UUID authorizationId,
        String cardId,
        BigDecimal amount,
        String currency,
        Instant postedAt
) {

    public static StatementItemResponse from(StatementItem item) {
        return new StatementItemResponse(
                item.id(),
                item.cardTransactionId(),
                item.networkTransactionId(),
                item.authorizationId(),
                item.cardId(),
                item.amount().amount(),
                item.amount().currency().getCurrencyCode(),
                item.postedAt()
        );
    }
}
