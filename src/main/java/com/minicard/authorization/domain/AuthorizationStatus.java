package com.minicard.authorization.domain;

public enum AuthorizationStatus {
    PENDING,
    APPROVED,
    POSTED,
    DECLINED,
    EXPIRED;

    public boolean isApproved() {
        return this == APPROVED;
    }
}
