package com.minicard.statement.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.minicard.statement.domain.Statement;

/**
 * Statement 查询 read model。
 *
 * <p>Statement aggregate 负责还款状态转换和不变量；read model 只负责 GET response。
 * 缓存这个对象比缓存 aggregate 风险更低，也更接近生产系统里的 query projection。</p>
 */
public record StatementReadModel(
        UUID id,
        UUID creditAccountId,
        LocalDate periodStart,
        LocalDate periodEnd,
        LocalDate dueDate,
        BigDecimal totalAmount,
        BigDecimal minimumPaymentAmount,
        BigDecimal paidAmount,
        String currency,
        int transactionCount,
        String status,
        Instant generatedAt,
        List<StatementItemReadModel> items
) {

    public static StatementReadModel from(Statement statement) {
        return new StatementReadModel(
                statement.id(),
                statement.creditAccountId(),
                statement.periodStart(),
                statement.periodEnd(),
                statement.dueDate(),
                statement.totalAmount().amount(),
                statement.minimumPaymentAmount().amount(),
                statement.paidAmount().amount(),
                statement.totalAmount().currency().getCurrencyCode(),
                statement.transactionCount(),
                statement.status().name(),
                statement.generatedAt(),
                statement.items().stream()
                        .map(StatementItemReadModel::from)
                        .toList()
        );
    }
}
