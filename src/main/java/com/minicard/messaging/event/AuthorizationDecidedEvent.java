package com.minicard.messaging.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Public integration contract for an authorization's final decision.
 *
 * <p>The event contains an internal card token, never a plaintext PAN. Likely
 * future consumers include cardholder notification, risk-feature analytics,
 * and operations/audit projections.</p>
 */
public record AuthorizationDecidedEvent(
        UUID authorizationId,
        String cardId,
        String amount,
        String currency,
        String status,
        String declineReason,
        Instant decidedAt
) {

    public static final String EVENT_TYPE = "authorization.decided";
    public static final int EVENT_VERSION = 1;
}
