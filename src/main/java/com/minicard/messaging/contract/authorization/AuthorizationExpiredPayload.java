package com.minicard.messaging.contract.authorization;

import java.time.Instant;
import java.util.UUID;

/**
 * Authorization 对外发布的 public message contract：authorization.expired。
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
