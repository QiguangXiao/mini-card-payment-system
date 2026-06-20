package com.minicard.risk.application;

/**
 * Risk feature projection 关心的授权决策结果。
 *
 * <p>这里故意只保留 APPROVED/DECLINED，因为风控 velocity 特征只统计授权决策历史；
 * authorization.posted/expired 属于授权后续生命周期，不应该混进这个投影。</p>
 */
public enum AuthorizationDecisionOutcome {
    APPROVED,
    DECLINED
}
