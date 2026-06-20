package com.minicard.repayment.api.dto;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * 创建还款的 HTTP request DTO。
 *
 * <p>DTO 只表达 API contract 和输入校验；还款金额进入 application 后会被转换成 Money。</p>
 */
public record ReceiveRepaymentRequest(
        @NotNull UUID statementId,
        @NotNull @DecimalMin(value = "0.01") @Digits(integer = 17, fraction = 2) BigDecimal amount,
        @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency
) {
}
