package com.minicard.authorization.domain;

/**
 * Authorization 生命周期状态。
 *
 * <p>关键词：授权状态, 预授权, 过期, authorization status,
 * hold lifecycle, expiry, オーソリ状態(オーソリじょうたい),
 * 有効期限切れ(ゆうこうきげんぎれ)。</p>
 */
public enum AuthorizationStatus {
    /** 已创建但尚未做风险/额度决策。 */
    PENDING,
    /** 已批准并占用额度 reservation。 */
    APPROVED,
    /** presentment 已入账，authorization 已被消费。 */
    POSTED,
    /** 风险、卡状态或额度不足导致拒绝。 */
    DECLINED,
    /** 到期未入账，reservation 已释放。 */
    EXPIRED;

    /**
     * 业务上只有 APPROVED 可以继续 presentment posting。
     */
    public boolean isApproved() {
        return this == APPROVED;
    }
}
