package com.minicard.risk.domain;

import java.util.Objects;
import java.util.Optional;

public record RiskDecision(
        boolean approved,
        RiskDeclineReason declineReason,
        int score
) {

    public RiskDecision {
        if (score < 0 || score > 100) {
            throw new IllegalArgumentException("risk score must be between 0 and 100");
        }
        if (approved && declineReason != null) {
            throw new IllegalArgumentException("approved risk decision cannot have reason");
        }
        if (!approved) {
            Objects.requireNonNull(declineReason, "declined risk decision requires a reason");
        }
    }

    public static RiskDecision approve(int score) {
        return new RiskDecision(true, null, score);
    }

    public static RiskDecision decline(RiskDeclineReason reason, int score) {
        return new RiskDecision(false, reason, score);
    }

    public Optional<RiskDeclineReason> optionalDeclineReason() {
        return Optional.ofNullable(declineReason);
    }
}
