package com.minicard.ledger.domain;

/**
 * Ledger entry 的业务类型。
 *
 * <p>关键词：账本分录类型, 业务事实, ledger entry type,
 * accounting fact, 仕訳種別(しわけしゅべつ)。</p>
 */
public enum LedgerEntryType {

    /**
     * 来自 card_transaction.posted，表示持卡人消费正式入账。
     */
    CARD_TRANSACTION_POSTED,

    /**
     * 来自 repayment.received，表示客户还款正式入账。
     */
    REPAYMENT_RECEIVED
}
