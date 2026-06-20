package com.minicard.card.domain;

/**
 * Card 可用状态。
 *
 * <p>关键词：卡状态, 可用卡, 冻结卡, card status,
 * active card, blocked card, カード状態(カードじょうたい),
 * 利用停止(りようていし)。</p>
 */
public enum CardStatus {
    /** 可以发起 authorization。 */
    ACTIVE,
    /** 被风控/客服冻结，不能授权。 */
    BLOCKED,
    /** 卡片已过期，不能授权。 */
    EXPIRED
}
