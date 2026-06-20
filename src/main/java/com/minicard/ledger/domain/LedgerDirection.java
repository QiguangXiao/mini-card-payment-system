package com.minicard.ledger.domain;

/**
 * Ledger entry 的借贷方向。
 *
 * <p>关键词：借贷方向, 账本方向, ledger direction,
 * debit, credit, 仕訳方向(しわけほうこう)。</p>
 */
public enum LedgerDirection {

    /**
     * 借方。当前最小 Ledger 里，消费入账会增加 issuer 对持卡人的应收款。
     */
    DEBIT,

    /**
     * 贷方。当前最小 Ledger 里，还款入账会减少 issuer 对持卡人的应收款。
     */
    CREDIT
}
