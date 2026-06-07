package com.minicard.authorization.domain;

public class InvalidAuthorizationStateException extends RuntimeException {

    public InvalidAuthorizationStateException(AuthorizationStatus currentStatus, String action) {
        super("cannot " + action + " authorization in status " + currentStatus);
    }
}
