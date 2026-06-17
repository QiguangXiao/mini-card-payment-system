package com.minicard.authorization.infrastructure.messaging.payload;

import java.time.Instant;
import java.util.UUID;

/**
 * Kafka/Outbox 对外 payload：authorization.expired。
 */
public record AuthorizationExpiredPayload(
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
