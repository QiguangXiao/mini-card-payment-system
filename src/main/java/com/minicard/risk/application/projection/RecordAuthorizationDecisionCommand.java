package com.minicard.risk.application.projection;

import java.time.Instant;
import java.util.UUID;

/**
 * Transport-neutral fact used to update the Risk feature projection.
 */
public record RecordAuthorizationDecisionCommand(
        UUID eventId,
        String cardId,
        String status,
        Instant decidedAt
) {
}
