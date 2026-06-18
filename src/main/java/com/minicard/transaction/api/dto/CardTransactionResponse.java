package com.minicard.transaction.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.minicard.transaction.domain.CardTransaction;

public record CardTransactionResponse(
        UUID id,
        String networkTransactionId,
        UUID authorizationId,
        String cardId,
        UUID creditAccountId,
        BigDecimal amount,
        String currency,
        String status,
        Instant presentmentReceivedAt,
        Instant postedAt
) {

    public static CardTransactionResponse from(CardTransaction transaction) {
        return new CardTransactionResponse(
                transaction.id(),
                transaction.networkTransactionId(),
                transaction.authorizationId(),
                transaction.cardId(),
                transaction.creditAccountId(),
                transaction.amount().amount(),
                transaction.amount().currency().getCurrencyCode(),
                transaction.status().name(),
                transaction.presentmentReceivedAt(),
                transaction.postedAt()
        );
    }
}
