package com.minicard.card.domain;

/**
 * Card 层授权前置校验失败原因。
 *
 * <p>关键词：卡校验失败, 卡冻结, 卡过期, card authorization failure,
 * blocked card, expired card, カード検証失敗(カードけんしょうしっぱい),
 * 有効期限切れ(ゆうこうきげんぎれ)。</p>
 */
public enum CardAuthorizationFailure {
    /** 卡被冻结。 */
    CARD_BLOCKED,
    /** 卡已过期。 */
    CARD_EXPIRED
}
