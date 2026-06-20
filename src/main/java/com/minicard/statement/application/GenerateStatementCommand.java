package com.minicard.statement.application;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * 生成账单 use case 的输入 command。
 *
 * <p>真实主路径由 StatementBatchService 根据固定 billing day 自动构造。
 * 手动 API 仍复用同一个 command，方便本地学习、测试和运营 backfill。</p>
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
