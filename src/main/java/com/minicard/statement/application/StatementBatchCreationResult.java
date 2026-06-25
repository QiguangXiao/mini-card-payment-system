package com.minicard.statement.application;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Billing cycle scheduler 创建 batch 的结果。
 *
 * <p>关键词：账单批次创建结果, daily scheduler, batch creation,
 * billing result, 作成結果(さくせいけっか)。</p>
 */
public record StatementBatchCreationResult(
        boolean due,
        boolean created,
        LocalDate runDate,
        LocalDate periodStart,
        LocalDate periodEnd,
        LocalDate dueDate,
        UUID batchId,
        long accountCount,
        int jobCount
) {

    public static StatementBatchCreationResult notDue(LocalDate runDate) {
        return new StatementBatchCreationResult(
                false,
                false,
                runDate,
                null,
                null,
                null,
                null,
                0,
                0
        );
    }
}
