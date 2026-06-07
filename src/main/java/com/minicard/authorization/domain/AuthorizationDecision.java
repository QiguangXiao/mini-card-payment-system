package com.minicard.authorization.domain;

import java.util.Objects;
import java.util.Optional;

public record AuthorizationDecision(
        boolean approved,
        AuthorizationDeclineReason declineReason
) {

    public AuthorizationDecision {
        if (approved && declineReason != null) {
            throw new IllegalArgumentException("approved decision cannot have a decline reason");
        }
        if (!approved) {
            Objects.requireNonNull(declineReason, "declined decision requires a reason");
        }
    }

    public static AuthorizationDecision approve() {
        return new AuthorizationDecision(true, null);
    }

    public static AuthorizationDecision decline(AuthorizationDeclineReason reason) {
        return new AuthorizationDecision(false, reason);
    }

    public Optional<AuthorizationDeclineReason> optionalDeclineReason() {
        return Optional.ofNullable(declineReason);
    }
}
