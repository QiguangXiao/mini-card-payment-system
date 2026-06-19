package com.minicard.transaction.domain.event;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.minicard.authorization.domain.Money;

public record CardTransactionPostedDomainEvent(
        UUID cardTransactionId,
        String networkTransactionId,
        UUID authorizationId,
        String cardId,
        UUID creditAccountId,
        Money amount,
        Instant occurredAt
) implements CardTransactionDomainEvent {

    public CardTransactionPostedDomainEvent {
        Objects.requireNonNull(cardTransactionId);
        Objects.requireNonNull(networkTransactionId);
        Objects.requireNonNull(authorizationId);
        Objects.requireNonNull(cardId);
        Objects.requireNonNull(creditAccountId);
        Objects.requireNonNull(amount);
        Objects.requireNonNull(occurredAt);
    }
}
