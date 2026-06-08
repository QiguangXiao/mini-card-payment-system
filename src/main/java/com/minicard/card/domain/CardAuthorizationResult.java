package com.minicard.card.domain;

import java.util.Objects;
import java.util.Optional;

public record CardAuthorizationResult(
        boolean eligible,
        CardAuthorizationFailure failure
) {

    public CardAuthorizationResult {
        if (eligible && failure != null) {
            throw new IllegalArgumentException("eligible card cannot have a failure");
        }
        if (!eligible) {
            Objects.requireNonNull(failure, "ineligible card requires a reason");
        }
    }

    public static CardAuthorizationResult allowed() {
        return new CardAuthorizationResult(true, null);
    }

    public static CardAuthorizationResult rejected(CardAuthorizationFailure failure) {
        return new CardAuthorizationResult(false, failure);
    }

    public Optional<CardAuthorizationFailure> optionalFailure() {
        return Optional.ofNullable(failure);
    }
}
