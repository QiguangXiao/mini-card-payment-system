package com.minicard.statement.infrastructure.mybatis;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * statement_lines 表的 MyBatis row DTO。
 *
 * <p>关键词：账单明细, 交易快照, 账本引用, statement line,
 * transaction snapshot, ledger reference, 請求明細(せいきゅうめいさい),
 * 仕訳参照(しわけさんしょう)。</p>
 */
public record StatementLineRow(
        /** statement line 主键。 */
        String id,
        /** 所属 statement。 */
        String statementId,
        /** 对应 issuer-side card transaction。 */
        String cardTransactionId,
        /** 对应 append-only ledger entry；老数据迁移时可能为空，新生成 line 必须有值。 */
        String ledgerEntryId,
        /** 卡组织/商户侧交易号，用于对账。 */
        String networkTransactionId,
        /** 原 authorization id。 */
        String authorizationId,
        /** 交易发生的 card id。 */
        String cardId,
        /** 明细金额。 */
        BigDecimal amount,
        /** 明细货币。 */
        String currency,
        /** presentment posted 时间。 */
        Instant postedAt,
        /** 明细创建时间。 */
        Instant createdAt
) {
}
