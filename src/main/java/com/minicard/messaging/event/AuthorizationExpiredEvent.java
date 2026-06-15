package com.minicard.messaging.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Public integration contract emitted after an authorization hold expires.
 */
public record AuthorizationExpiredEvent(
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
