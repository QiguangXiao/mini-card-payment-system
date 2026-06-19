package com.minicard.statement.api.dto;

import java.time.LocalDate;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;

public record GenerateStatementRequest(
        @NotNull
        UUID creditAccountId,

        @NotNull
        LocalDate periodStart,

        @NotNull
        LocalDate periodEnd,

        @NotNull
        LocalDate dueDate
) {
}
