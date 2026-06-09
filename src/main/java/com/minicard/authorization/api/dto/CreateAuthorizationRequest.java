package com.minicard.authorization.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateAuthorizationRequest(
        @NotBlank @Size(max = 100) String cardId,
        @NotNull @DecimalMin(value = "0.01") @Digits(integer = 17, fraction = 2) BigDecimal amount,
        @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
        @NotBlank @Size(max = 100) String merchantId,
        @NotBlank @Pattern(regexp = "[A-Z]{2}") String merchantCountry,
        @NotBlank @Pattern(regexp = "[A-Z]{2}") String cardholderCountry
) {
}
