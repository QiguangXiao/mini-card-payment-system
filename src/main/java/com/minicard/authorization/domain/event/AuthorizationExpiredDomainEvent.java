package com.minicard.authorization.domain.event;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.minicard.authorization.domain.Money;

public record AuthorizationExpiredDomainEvent(
        UUID authorizationId,
        String cardId,
        Money requestedAmount,
        Instant expiresAt,
        Instant occurredAt
) implements AuthorizationDomainEvent {

    public AuthorizationExpiredDomainEvent {
        Objects.requireNonNull(authorizationId);
        Objects.requireNonNull(cardId);
        Objects.requireNonNull(requestedAmount);
        Objects.requireNonNull(expiresAt);
        Objects.requireNonNull(occurredAt);
    }
}
