package com.minicard.transaction.domain;

/**
 * CardTransaction 的账单归属状态。
 *
 * <p>关键词：交易出账状态, 未出账, 已出账, billing status,
 * billed marker, 請求済み(せいきゅうずみ), 未請求(みせいきゅう)。</p>
 *
 * <p>status=POSTED 说明交易已经入账到用户可见流水；billingStatus=BILLED
 * 说明它已经被某一期 statement line 快照收录。两者不是同一件事。</p>
 */
public enum CardTransactionBillingStatus {
    /** 已入账但还没有进入 statement。 */
    UNBILLED,
    /** 已经被 statement line 收录。 */
    BILLED
}
