package com.minicard.risk.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * 单卡长期风险画像。
 *
 * <p>关键词：风控特征, 长窗口画像, CQRS projection, risk feature,
 * historical decline ratio, リスク特徴量(リスクとくちょうりょう)。</p>
 *
 * <p>它来自 Kafka consumer 异步维护的 `card_risk_features` projection。
 * 这不是 Redis 短窗口 velocity，而是 eventually consistent 的历史画像。</p>
 */
public record CardRiskFeature(
        String cardId,
        long authorizationCount,
        long approvedCount,
        long declinedCount,
        Instant lastDecisionAt
) {

    public CardRiskFeature {
        if (cardId == null || cardId.isBlank()) {
            throw new IllegalArgumentException("cardId must not be blank");
        }
        if (authorizationCount < 0 || approvedCount < 0 || declinedCount < 0) {
            throw new IllegalArgumentException("risk feature counts must be non-negative");
        }
        if (approvedCount + declinedCount != authorizationCount) {
            throw new IllegalArgumentException("approvedCount + declinedCount must equal authorizationCount");
        }
        if (lastDecisionAt == null) {
            throw new IllegalArgumentException("lastDecisionAt must not be null");
        }
    }

    public boolean hasEnoughSample(long minimumAuthorizationCount) {
        return authorizationCount >= minimumAuthorizationCount;
    }

    public boolean declineRateAtLeast(BigDecimal threshold) {
        if (authorizationCount == 0) {
            return false;
        }
        BigDecimal declineRate = BigDecimal.valueOf(declinedCount)
                .divide(BigDecimal.valueOf(authorizationCount), 4, RoundingMode.HALF_UP);
        return declineRate.compareTo(threshold) >= 0;
    }
}
