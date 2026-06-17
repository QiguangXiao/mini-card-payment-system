package com.minicard.authorization.integration.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Authorization declined 的 public integration contract。
 */
public record AuthorizationDeclinedIntegrationEvent(
        UUID authorizationId,
        String cardId,
        String amount,
        String currency,
        String declineReason,
        Instant declinedAt
) {

    public static final String EVENT_TYPE = "authorization.declined";
    public static final int EVENT_VERSION = 1;
}
