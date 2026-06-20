package com.minicard.transaction.api.dto;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 创建 presentment 的 API request。
 *
 * <p>关键词：入账请求, presentment, Bean Validation, create presentment request,
 * API boundary, 売上データ(うりあげデータ), 入力検証(にゅうりょくけんしょう)。</p>
 */
public record CreatePresentmentRequest(
        /** 外部网络交易 id，作为 presentment 幂等键。 */
        @NotBlank @Size(max = 100)
        String networkTransactionId,

        /** 对应已批准 authorization。 */
        @NotNull
        UUID authorizationId,

        /** 入账金额，必须大于 0。 */
        @NotNull @DecimalMin(value = "0.00", inclusive = false)
        BigDecimal amount,

        /** ISO 4217 三位货币代码。 */
        @NotBlank @Size(min = 3, max = 3)
        String currency
) {
}
