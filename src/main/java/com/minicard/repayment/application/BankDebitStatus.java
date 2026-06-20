package com.minicard.repayment.application;

/**
 * 银行扣款状态（bank debit status / 口座振替ステータス）。
 *
 * <p>关键词：银行状态, 成功, 失败, bank debit status, success,
 * failed, 口座振替ステータス(こうざふりかえステータス),
 * 成功(せいこう), 失敗(しっぱい)。</p>
 */
public enum BankDebitStatus {
    /** 银行已确认扣款成功，可以进入还款入账。 */
    SUCCESS,
    /** 银行扣款失败，不能减少 statement/account 的应还金额。 */
    FAILED
}
