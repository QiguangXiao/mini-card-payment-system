package com.minicard.authorization.application;

import java.util.UUID;

public class AuthorizationNotFoundException extends RuntimeException {

    public AuthorizationNotFoundException(UUID id) {
        super("authorization not found: " + id);
    }
}
