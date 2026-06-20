package com.minicard.ledger.domain;

/**
 * Ledger entry 来源业务对象类型。
 */
public enum LedgerSourceType {

    /**
     * 用户可见交易流水。
     */
    CARD_TRANSACTION,

    /**
     * 还款业务对象。
     */
    REPAYMENT
}
