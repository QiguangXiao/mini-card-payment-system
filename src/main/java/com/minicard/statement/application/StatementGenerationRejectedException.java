package com.minicard.statement.application;

public class StatementGenerationRejectedException extends RuntimeException {

    public StatementGenerationRejectedException(String message) {
        super(message);
    }
}
