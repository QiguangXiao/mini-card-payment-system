package com.minicard.statement.infrastructure.mybatis;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Statement line source 的 SQL projection row。
 *
 * <p>关键词：账单来源行, 交易账本连接, SQL projection,
 * statement line source row, ledger join, SQL射影(エスキューエルしゃえい)。</p>
 */
public record StatementLineSourceRow(
        String cardTransactionId,
        String ledgerEntryId,
        String networkTransactionId,
        String authorizationId,
        String cardId,
        BigDecimal amount,
        String currency,
        Instant postedAt
) {
}
