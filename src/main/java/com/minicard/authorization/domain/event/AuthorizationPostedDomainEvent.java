package com.minicard.authorization.domain.event;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.minicard.authorization.domain.Money;

public record AuthorizationPostedDomainEvent(
        UUID authorizationId,
        String cardId,
        Money requestedAmount,
        Instant occurredAt
) implements AuthorizationDomainEvent {

    public AuthorizationPostedDomainEvent {
        Objects.requireNonNull(authorizationId);
        Objects.requireNonNull(cardId);
        Objects.requireNonNull(requestedAmount);
        Objects.requireNonNull(occurredAt);
    }
}
