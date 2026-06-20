package com.minicard.repayment.application;

public class RepaymentConflictException extends RuntimeException {

    public RepaymentConflictException() {
        super("repayment idempotency key was already used with a different request");
    }
}
