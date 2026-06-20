package com.minicard.transaction.domain;

/**
 * CardTransaction 状态。
 *
 * <p>关键词：交易状态, presentment, 已入账, card transaction status,
 * pending transaction, posted transaction, 取引状態(とりひきじょうたい),
 * 売上処理(うりあげしょり)。</p>
 */
public enum CardTransactionStatus {
    /** 已收到 presentment 但尚未完成入账。 */
    PENDING,
    /** 已完成入账，可被 statement batch 纳入账单。 */
    POSTED
}
