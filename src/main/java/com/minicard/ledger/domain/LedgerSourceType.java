package com.minicard.ledger.domain;

/**
 * Ledger entry 来源业务对象类型。
 */
public enum LedgerSourceType {

    /**
     * 用户可见交易流水。
     */
    // enum 固定审计查询维度；如果 sourceType 写自由字符串，CARD_TRANSACTION/cardTransaction 会分裂成两类数据。
    CARD_TRANSACTION,

    /**
     * 还款业务对象。
     */
    REPAYMENT
}
