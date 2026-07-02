package com.minicard.repayment.infrastructure.mybatis;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Repayment 的数据库行模型。Domain 不关心列名和 String/UUID 转换。
 */
public record RepaymentRow(
        /** repayment 主键。 */
        String id,
        /** API idempotency key，唯一键防止同一次还款 retry 重复入账。 */
        String idempotencyKey,
        /** 请求指纹，用于识别同 key 不同 body 的冲突。 */
        String requestFingerprint,
        /** 被还款的 statement id。 */
        String statementId,
        /** statement 所属 credit account id；PENDING claim 初期可能还未补齐。 */
        String creditAccountId,
        /** 还款金额。 */
        BigDecimal amount,
        /** 币种代码，例如 JPY。 */
        String currency,
        /** RepaymentStatus 字符串。 */
        String status,
        /** 成功入账时间；PENDING 时为空。 */
        Instant receivedAt,
        /** 创建时间。 */
        Instant createdAt,
        /** 更新时间。 */
        Instant updatedAt
) {
}
