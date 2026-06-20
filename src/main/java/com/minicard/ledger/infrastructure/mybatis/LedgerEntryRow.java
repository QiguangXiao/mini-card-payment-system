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
        String id,
        String sourceEventId,
        String entryType,
        String direction,
        String sourceType,
        String sourceId,
        String creditAccountId,
        BigDecimal amount,
        String currency,
        Instant occurredAt,
        Instant createdAt
) {
}
