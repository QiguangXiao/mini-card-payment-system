package com.minicard.risk.domain;

public enum RiskDeclineReason {
    VELOCITY_EXCEEDED,
    HIGH_RISK_AMOUNT,
    GEOLOCATION_MISMATCH,
    BLOCKED_MERCHANT,
    EXTERNAL_RISK_DECLINED,
    EXTERNAL_RISK_UNAVAILABLE
}
