package com.minicard.authorization.infrastructure.messaging.payload;

import java.time.Instant;
import java.util.UUID;

/**
 * Kafka/Outbox 对外 payload：authorization.declined。
 */
public record AuthorizationDeclinedPayload(
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
