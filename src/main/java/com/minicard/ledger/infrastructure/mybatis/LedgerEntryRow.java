package com.minicard.ledger.infrastructure.mybatis;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * ledger_entries 表的 MyBatis row DTO。
 *
 * <p>Database row 与 domain aggregate 分离，避免 SQL 字段名、String enum、String UUID
 * 泄漏到 LedgerEntry 领域对象。</p>
 */
public record LedgerEntryRow(
        /** ledger entry 主键，数据库中以字符串保存 UUID。 */
        String id,
        /** 来源 integration event id；和唯一键一起防止 consumer replay 重复记账。 */
        String sourceEventId,
        /** LedgerEntryType 字符串。 */
        String entryType,
        /** LedgerDirection 字符串；金额为正，方向表达应收增减。 */
        String direction,
        /** LedgerSourceType 字符串，例如 CARD_TRANSACTION 或 REPAYMENT。 */
        String sourceType,
        /** 来源业务对象 id，例如 cardTransactionId 或 repaymentId。 */
        String sourceId,
        /** 所属 credit account id。 */
        String creditAccountId,
        /** 分录金额。 */
        BigDecimal amount,
        /** 币种代码，例如 JPY。 */
        String currency,
        /** 业务事实发生时间，例如 postedAt 或 receivedAt。 */
        Instant occurredAt,
        /** ledger row 创建时间，可能晚于 occurredAt。 */
        Instant createdAt
) {
}
