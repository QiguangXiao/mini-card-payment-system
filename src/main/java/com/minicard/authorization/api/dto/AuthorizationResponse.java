package com.minicard.authorization.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.minicard.authorization.domain.Authorization;

/**
 * 授权 API response DTO，把 domain state 映射成客户端可读的 JSON contract。
 *
 * <p>这里保留 declineReason/expiresAt/expiredAt 等字段，是为了让学习和排查时能看到
 * authorization lifecycle，而不是只返回一个 approved boolean。</p>
 */
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
        // API mapping 放在 DTO，避免 domain object 直接暴露给 HTTP 层。
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
