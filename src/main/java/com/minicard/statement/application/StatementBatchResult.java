package com.minicard.statement.application;

import java.time.LocalDate;

public record StatementBatchResult(
        boolean due,
        LocalDate runDate,
        LocalDate periodStart,
        LocalDate periodEnd,
        LocalDate dueDate,
        int candidateCount,
        int generatedCount,
        int skippedCount,
        int failedCount
) {

    public static StatementBatchResult notDue(LocalDate runDate) {
        return new StatementBatchResult(
                false,
                runDate,
                null,
                null,
                null,
                0,
                0,
                0,
                0
        );
    }
}
