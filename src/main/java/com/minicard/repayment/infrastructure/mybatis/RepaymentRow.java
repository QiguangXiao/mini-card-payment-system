package com.minicard.repayment.infrastructure.mybatis;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Repayment 的数据库行模型。Domain 不关心列名和 String/UUID 转换。
 */
public record RepaymentRow(
        String id,
        String idempotencyKey,
        String requestFingerprint,
        String statementId,
        String creditAccountId,
        BigDecimal amount,
        String currency,
        String status,
        Instant receivedAt,
        Instant createdAt,
        Instant updatedAt
) {
}
