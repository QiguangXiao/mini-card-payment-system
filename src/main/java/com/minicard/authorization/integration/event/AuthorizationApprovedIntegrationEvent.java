package com.minicard.authorization.integration.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Authorization approved 的 public integration contract。
 */
public record AuthorizationApprovedIntegrationEvent(
        UUID authorizationId,
        String cardId,
        String amount,
        String currency,
        Instant approvedAt,
        Instant expiresAt
) {

    public static final String EVENT_TYPE = "authorization.approved";
    public static final int EVENT_VERSION = 1;
}
