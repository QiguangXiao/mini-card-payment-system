package com.minicard.repayment.application;

import java.util.UUID;

public record AutoRepaymentResult(
        AutoRepaymentOutcome outcome,
        UUID statementId,
        UUID repaymentId
) {

    public static AutoRepaymentResult succeeded(UUID statementId, UUID repaymentId) {
        return new AutoRepaymentResult(AutoRepaymentOutcome.SUCCEEDED, statementId, repaymentId);
    }

    public static AutoRepaymentResult alreadyPaid(UUID statementId) {
        return new AutoRepaymentResult(AutoRepaymentOutcome.ALREADY_PAID, statementId, null);
    }
}
