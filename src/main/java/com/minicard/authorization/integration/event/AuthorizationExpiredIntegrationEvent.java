package com.minicard.authorization.integration.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Authorization hold expired 的 public integration contract。
 */
public record AuthorizationExpiredIntegrationEvent(
        UUID authorizationId,
        String cardId,
        String amount,
        String currency,
        Instant expiresAt,
        Instant expiredAt
) {

    public static final String EVENT_TYPE = "authorization.expired";
    public static final int EVENT_VERSION = 1;
}
