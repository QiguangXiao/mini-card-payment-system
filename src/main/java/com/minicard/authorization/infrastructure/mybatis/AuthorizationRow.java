package com.minicard.authorization.infrastructure.mybatis;

import java.math.BigDecimal;
import java.time.Instant;

public record AuthorizationRow(
        String id,
        String requestFingerprint,
        String cardId,
        BigDecimal amount,
        String currency,
        String status,
        String declineReason,
        Instant createdAt,
        Instant decidedAt,
        Instant expiresAt,
        Instant postedAt,
        Instant expiredAt
) {
}
