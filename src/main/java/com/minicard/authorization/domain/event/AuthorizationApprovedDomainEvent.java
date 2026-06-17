package com.minicard.authorization.domain.event;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.minicard.authorization.domain.Money;

public record AuthorizationApprovedDomainEvent(
        UUID authorizationId,
        String cardId,
        Money requestedAmount,
        Instant occurredAt,
        Instant expiresAt
) implements AuthorizationDomainEvent {

    public AuthorizationApprovedDomainEvent {
        Objects.requireNonNull(authorizationId);
        Objects.requireNonNull(cardId);
        Objects.requireNonNull(requestedAmount);
        Objects.requireNonNull(occurredAt);
        Objects.requireNonNull(expiresAt);
    }
}
