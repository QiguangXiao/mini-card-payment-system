package com.minicard.repayment.application;

import java.time.LocalDate;
import java.util.UUID;

import com.minicard.authorization.domain.Money;

/**
 * 银行扣款请求。
 *
 * <p>关键词：扣款请求, 应还金额, 付款日, debit request,
 * remaining amount, due date, 振替依頼(ふりかえいらい),
 * 請求残高(せいきゅうざんだか), 支払日(しはらいび)。</p>
 *
 * <p>真实系统还会包含 bank account / mandate id；当前学习项目暂时假设客户已有默认口座振替授权。</p>
 */
public record BankDebitRequest(
        /** 要扣款的 statement，作为银行请求的业务 reference。 */
        UUID statementId,
        /** 客户信用账户 id，方便未来关联扣款账户授权。 */
        UUID creditAccountId,
        /** 扣款金额，沿用 Money 保留 currency/scale 语义。 */
        Money amount,
        /** 计划扣款日（支払日 / due date），用于银行请求和对账。 */
        LocalDate dueDate
) {
}
