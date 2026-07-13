package com.minicard.statement.infrastructure.mybatis;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Statement line source 的 SQL projection row。
 *
 * <p>关键词：账单来源行, 交易快照, SQL projection,
 * statement line source row, SQL射影(エスキューエルしゃえい)。</p>
 */
public record StatementLineSourceRow(
        /** 待出账的 card transaction id。 */
        String cardTransactionId,
        /** 外部网络交易 id，用于对账和展示。 */
        String networkTransactionId,
        /** 原始 authorization id。 */
        String authorizationId,
        /** 消费发生的 card id。 */
        String cardId,
        /** 待出账金额。 */
        BigDecimal amount,
        /** 币种代码。 */
        String currency,
        /** 交易 posted 时间，决定是否落入本 billing cycle。 */
        Instant postedAt
) {
}
