package com.minicard.risk.domain;

import java.util.Objects;
import java.util.Optional;

/**
 * 风控决策值对象。
 *
 * <p>关键词：风控决策, 拒绝原因, 风险评分, risk decision,
 * decline reason, risk score, リスク判定(リスクはんてい),
 * スコア。</p>
 */
public record RiskDecision(
        /** true 表示风控允许继续 authorization。 */
        boolean approved,
        /** 拒绝原因；approved 时为空。 */
        RiskDeclineReason declineReason,
        /** 0-100 风险评分。 */
        int score
) {

    public RiskDecision {
        if (score < 0 || score > 100) {
            // score 范围固定，避免不同风控来源混用不可比较的评分。
            throw new IllegalArgumentException("risk score must be between 0 and 100");
        }
        if (approved && declineReason != null) {
            throw new IllegalArgumentException("approved risk decision cannot have reason");
        }
        if (!approved) {
            Objects.requireNonNull(declineReason, "declined risk decision requires a reason");
        }
    }

    /**
     * 构造风控通过结果。
     */
    public static RiskDecision approve(int score) {
        return new RiskDecision(true, null, score);
    }

    /**
     * 构造风控拒绝结果。
     */
    public static RiskDecision decline(RiskDeclineReason reason, int score) {
        return new RiskDecision(false, reason, score);
    }

    /**
     * Optional 包装拒绝原因，避免调用方直接处理 null。
     */
    public Optional<RiskDeclineReason> optionalDeclineReason() {
        return Optional.ofNullable(declineReason);
    }
}
