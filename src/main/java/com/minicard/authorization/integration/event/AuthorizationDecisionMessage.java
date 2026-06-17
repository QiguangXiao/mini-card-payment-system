package com.minicard.authorization.integration.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Consumer 内部使用的授权决策视图。
 *
 * <p>外部 contract 仍是 authorization.approved / authorization.declined 两种事件；
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
            AuthorizationApprovedIntegrationEvent payload
    ) {
        return new AuthorizationDecisionMessage(
                eventId,
                AuthorizationApprovedIntegrationEvent.EVENT_TYPE,
                payload.authorizationId(),
                payload.cardId(),
                "APPROVED",
                payload.approvedAt()
        );
    }

    static AuthorizationDecisionMessage declined(
            UUID eventId,
            AuthorizationDeclinedIntegrationEvent payload
    ) {
        return new AuthorizationDecisionMessage(
                eventId,
                AuthorizationDeclinedIntegrationEvent.EVENT_TYPE,
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
