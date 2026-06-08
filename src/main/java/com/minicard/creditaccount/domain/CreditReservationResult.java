package com.minicard.creditaccount.domain;

import java.util.Objects;
import java.util.Optional;

public record CreditReservationResult(
        boolean reserved,
        CreditReservationFailure failure
) {

    public CreditReservationResult {
        if (reserved && failure != null) {
            throw new IllegalArgumentException("successful reservation cannot have a failure");
        }
        if (!reserved) {
            Objects.requireNonNull(failure, "failed reservation requires a reason");
        }
    }

    public static CreditReservationResult success() {
        return new CreditReservationResult(true, null);
    }

    public static CreditReservationResult rejected(CreditReservationFailure failure) {
        return new CreditReservationResult(false, failure);
    }

    public Optional<CreditReservationFailure> optionalFailure() {
        return Optional.ofNullable(failure);
    }
}
