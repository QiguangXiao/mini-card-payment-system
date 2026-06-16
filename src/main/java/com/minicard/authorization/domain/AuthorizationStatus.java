package com.minicard.authorization.domain;

public enum AuthorizationStatus {
    PENDING,
    APPROVED,
    DECLINED,
    EXPIRED;

    public boolean isApproved() {
        return this == APPROVED;
    }
}
