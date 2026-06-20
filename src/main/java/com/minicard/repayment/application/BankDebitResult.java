package com.minicard.repayment.application;

public record BankDebitResult(
        BankDebitStatus status,
        String failureReason
) {

    public static BankDebitResult success() {
        return new BankDebitResult(BankDebitStatus.SUCCESS, null);
    }

    public static BankDebitResult failed(String failureReason) {
        return new BankDebitResult(BankDebitStatus.FAILED, failureReason);
    }
}
