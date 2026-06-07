package com.minicard.authorization.application;

public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException() {
        super("idempotency key was already used with a different request");
    }
}
