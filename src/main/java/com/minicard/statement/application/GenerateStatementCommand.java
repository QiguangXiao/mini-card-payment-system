package com.minicard.statement.application;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * 生成账单 use case 的输入 command。
 *
 * <p>当前阶段先显式传入 billing cycle，方便用 API 学习和测试。
 * 未来可以由 scheduler 根据账户账单日自动构造同一个 command。</p>
 */
public record GenerateStatementCommand(
        UUID creditAccountId,
        LocalDate periodStart,
        LocalDate periodEnd,
        LocalDate dueDate
) {

    public GenerateStatementCommand {
        Objects.requireNonNull(creditAccountId, "creditAccountId must not be null");
        Objects.requireNonNull(periodStart, "periodStart must not be null");
        Objects.requireNonNull(periodEnd, "periodEnd must not be null");
        Objects.requireNonNull(dueDate, "dueDate must not be null");
        if (periodEnd.isBefore(periodStart)) {
            throw new IllegalArgumentException("periodEnd must not be before periodStart");
        }
        if (!dueDate.isAfter(periodEnd)) {
            throw new IllegalArgumentException("dueDate must be after periodEnd");
        }
    }
}
