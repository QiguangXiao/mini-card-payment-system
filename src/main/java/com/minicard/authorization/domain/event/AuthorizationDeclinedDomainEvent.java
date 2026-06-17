package com.minicard.authorization.domain.event;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.minicard.authorization.domain.AuthorizationDeclineReason;
import com.minicard.authorization.domain.Money;

public record AuthorizationDeclinedDomainEvent(
        UUID authorizationId,
        String cardId,
        Money requestedAmount,
        AuthorizationDeclineReason declineReason,
        Instant occurredAt
) implements AuthorizationDomainEvent {

    public AuthorizationDeclinedDomainEvent {
        Objects.requireNonNull(authorizationId);
        Objects.requireNonNull(cardId);
        Objects.requireNonNull(requestedAmount);
        Objects.requireNonNull(declineReason);
        Objects.requireNonNull(occurredAt);
    }
}
