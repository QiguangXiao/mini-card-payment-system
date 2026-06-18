package com.minicard.transaction.application;

public class PresentmentConflictException extends RuntimeException {

    public PresentmentConflictException() {
        super("networkTransactionId was reused for a different presentment request");
    }
}
