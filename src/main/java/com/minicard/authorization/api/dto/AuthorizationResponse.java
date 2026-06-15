package com.minicard.authorization.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.minicard.authorization.domain.Authorization;

public record AuthorizationResponse(
        UUID id,
        String cardId,
        BigDecimal amount,
        String currency,
        String status,
        String declineReason,
        Instant createdAt,
        Instant decidedAt,
        Instant expiresAt,
        Instant expiredAt
) {

    public static AuthorizationResponse from(Authorization authorization) {
        return new AuthorizationResponse(
                authorization.id(),
                authorization.cardId(),
                authorization.requestedAmount().amount(),
                authorization.requestedAmount().currency().getCurrencyCode(),
                authorization.status().name(),
                authorization.declineReason().map(Enum::name).orElse(null),
                authorization.createdAt(),
                authorization.decidedAt().orElse(null),
                authorization.expiresAt().orElse(null),
                authorization.expiredAt().orElse(null)
        );
    }
}
