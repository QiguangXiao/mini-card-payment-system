package com.minicard.creditaccount.domain;

/**
 * 额度占用失败原因。
 *
 * <p>关键词：额度占用失败, 币种不一致, 额度不足, credit reservation failure,
 * currency mismatch, insufficient credit, 利用可能額確保失敗(りようかのうがくかくほしっぱい),
 * ご利用可能額不足(ごりようかのうがくぶそく)。</p>
 */
public enum CreditReservationFailure {
    /** 账户被冻结。 */
    ACCOUNT_BLOCKED,
    /** 交易币种与账户币种不一致。 */
    CURRENCY_MISMATCH,
    /** 可用额度不足。 */
    INSUFFICIENT_AVAILABLE_CREDIT
}
