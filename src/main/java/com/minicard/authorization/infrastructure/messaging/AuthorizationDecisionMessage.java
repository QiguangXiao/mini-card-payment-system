package com.minicard.authorization.infrastructure.messaging;

import java.time.Instant;
import java.util.UUID;

import com.minicard.authorization.infrastructure.messaging.payload.AuthorizationApprovedPayload;
import com.minicard.authorization.infrastructure.messaging.payload.AuthorizationDeclinedPayload;

/**
 * Consumer 内部使用的授权决策视图。
 *
 * <p>外部 contract 仍是 authorization.approved / authorization.declined 两种 payload；
 * 这个 record 只是让 Notification/Risk 复用同一套 command mapping。</p>
 */
public record AuthorizationDecisionMessage(
        UUID eventId,
        String eventType,
        UUID authorizationId,
        String cardId,
        String status,
        Instant decidedAt
) {

    static AuthorizationDecisionMessage approved(
            UUID eventId,
            AuthorizationApprovedPayload payload
    ) {
        return new AuthorizationDecisionMessage(
                eventId,
                AuthorizationApprovedPayload.EVENT_TYPE,
                payload.authorizationId(),
                payload.cardId(),
                "APPROVED",
                payload.approvedAt()
        );
    }

    static AuthorizationDecisionMessage declined(
            UUID eventId,
            AuthorizationDeclinedPayload payload
    ) {
        return new AuthorizationDecisionMessage(
                eventId,
                AuthorizationDeclinedPayload.EVENT_TYPE,
                payload.authorizationId(),
                payload.cardId(),
                "DECLINED",
                payload.declinedAt()
        );
    }

    public boolean approved() {
        return "APPROVED".equals(status);
    }
}
