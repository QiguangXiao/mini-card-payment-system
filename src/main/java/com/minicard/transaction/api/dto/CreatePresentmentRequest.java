package com.minicard.transaction.api.dto;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreatePresentmentRequest(
        @NotBlank @Size(max = 100)
        String networkTransactionId,

        @NotNull
        UUID authorizationId,

        @NotNull @DecimalMin(value = "0.00", inclusive = false)
        BigDecimal amount,

        @NotBlank @Size(min = 3, max = 3)
        String currency
) {
}
