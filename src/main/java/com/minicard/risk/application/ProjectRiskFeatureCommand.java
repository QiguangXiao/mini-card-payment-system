package com.minicard.risk.application;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 从授权 integration event 更新 Risk feature projection 的 command。
 *
 * <p>Kafka listener 只负责 event contract -> command 映射；
 * RiskFeatureProjectionService 负责 Inbox idempotency 和数据库 side effect。</p>
 */
public record ProjectRiskFeatureCommand(
        UUID sourceEventId,
        String cardId,
        AuthorizationDecisionOutcome outcome,
        Instant decidedAt
) {

    public ProjectRiskFeatureCommand {
        Objects.requireNonNull(sourceEventId, "sourceEventId must not be null");
        if (cardId == null || cardId.isBlank()) {
            throw new IllegalArgumentException("cardId must not be blank");
        }
        Objects.requireNonNull(outcome, "outcome must not be null");
        Objects.requireNonNull(decidedAt, "decidedAt must not be null");
    }

    public static ProjectRiskFeatureCommand approved(
            UUID sourceEventId,
            String cardId,
            Instant approvedAt
    ) {
        return new ProjectRiskFeatureCommand(
                sourceEventId,
                cardId,
                AuthorizationDecisionOutcome.APPROVED,
                approvedAt
        );
    }

    public static ProjectRiskFeatureCommand declined(
            UUID sourceEventId,
            String cardId,
            Instant declinedAt
    ) {
        return new ProjectRiskFeatureCommand(
                sourceEventId,
                cardId,
                AuthorizationDecisionOutcome.DECLINED,
                declinedAt
        );
    }
}
