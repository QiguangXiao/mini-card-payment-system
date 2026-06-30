package com.minicard.statement.infrastructure.mybatis;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * statements 表的 MyBatis row DTO。
 *
 * <p>关键词：账单行, 持久化模型, 金额快照, statement row,
 * persistence row, amount snapshot, 請求行(せいきゅうぎょう),
 * 永続化(えいぞくか)。</p>
 *
 * <p>Row 对象只表达数据库字段，不放 domain invariant；Repository 负责和 Statement aggregate 转换。</p>
 */
public record StatementRow(
        /** statement 主键，数据库中以字符串保存 UUID。 */
        String id,
        /** 对应 credit account id。 */
        String creditAccountId,
        /** 账单周期开始日。 */
        LocalDate periodStart,
        /** 账单締め日。 */
        LocalDate periodEnd,
        /** 自动扣款/还款到期日。 */
        LocalDate dueDate,
        /** 本期账单总额。 */
        BigDecimal totalAmount,
        /** 最低还款额。 */
        BigDecimal minimumPaymentAmount,
        /** 已还金额；CLOSED 状态通常为 0。 */
        BigDecimal paidAmount,
        /** 货币代码，例如 JPY。 */
        String currency,
        /** 本期入账交易数量。 */
        int transactionCount,
        /** StatementStatus 的字符串形式。 */
        String status,
        /** read model 单调版本；每次账单展示状态变化都递增，用于 Redis CAS/tombstone。 */
        long version,
        /** statement 生成时间。 */
        Instant generatedAt,
        /** 数据库创建时间。 */
        Instant createdAt,
        /** 数据库更新时间。 */
        Instant updatedAt
) {
}
