package com.minicard.card.domain;

import java.util.Objects;
import java.util.Optional;

/**
 * Card 授权前置校验结果。
 *
 * <p>关键词：卡授权结果, 前置校验, 拒绝原因, card authorization result,
 * eligibility check, failure reason, カード判定結果(カードはんていけっか),
 * 拒否理由(きょひりゆう)。</p>
 */
public record CardAuthorizationResult(
        /** true 表示 card 层允许继续风控/额度判断。 */
        boolean eligible,
        /** 不允许时的失败原因。 */
        CardAuthorizationFailure failure
) {

    public CardAuthorizationResult {
        if (eligible && failure != null) {
            // 成功结果不能同时带失败原因，否则上层映射 decline reason 会混乱。
            throw new IllegalArgumentException("eligible card cannot have a failure");
        }
        if (!eligible) {
            Objects.requireNonNull(failure, "ineligible card requires a reason");
        }
    }

    /**
     * 构造允许结果。
     */
    public static CardAuthorizationResult allowed() {
        return new CardAuthorizationResult(true, null);
    }

    /**
     * 构造拒绝结果。
     */
    public static CardAuthorizationResult rejected(CardAuthorizationFailure failure) {
        return new CardAuthorizationResult(false, failure);
    }

    /**
     * 用 Optional 暴露失败原因，调用方不直接处理 null。
     */
    public Optional<CardAuthorizationFailure> optionalFailure() {
        return Optional.ofNullable(failure);
    }
}
