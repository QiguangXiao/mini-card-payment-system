package com.minicard.card.domain;

import java.util.Objects;
import java.util.UUID;

public record Card(
        String id,
        UUID creditAccountId,
        CardStatus status
) {

    public Card {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("card id must not be blank");
        }
        Objects.requireNonNull(creditAccountId);
        Objects.requireNonNull(status);
    }

    public CardAuthorizationResult checkAuthorizationEligibility() {
        // Card lifecycle is checked before touching the credit account. A
        // blocked or expired card should not consume account-lock time.
        return switch (status) {
            case ACTIVE -> CardAuthorizationResult.allowed();
            case BLOCKED -> CardAuthorizationResult.rejected(
                    CardAuthorizationFailure.CARD_BLOCKED
            );
            case EXPIRED -> CardAuthorizationResult.rejected(
                    CardAuthorizationFailure.CARD_EXPIRED
            );
        };
    }
}
