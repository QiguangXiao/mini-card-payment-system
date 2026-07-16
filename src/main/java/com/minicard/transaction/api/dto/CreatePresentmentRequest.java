package com.minicard.transaction.api.dto;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 创建 presentment 的 API request。
 *
 * <p>关键词：入账请求, presentment, Bean Validation, create presentment request,
 * API boundary, 売上データ(うりあげデータ), 入力検証(にゅうりょくけんしょう)。</p>
 */
// request DTO 用 record 是为了让 HTTP 输入不可变；如果用 setter POJO，
// 参数可能在 controller/service 之间被误改，也更难看出完整 API contract。
public record CreatePresentmentRequest(
        /** 外部网络交易 id，作为 presentment 幂等键。 */
        // Bean Validation 在进入 service 前 fail fast；domain 仍会保留 invariant，保护测试/批处理等非 HTTP 路径。
        @NotBlank @Size(max = 100)
        String networkTransactionId,

        /** 对应已批准 authorization。 */
        @NotNull
        UUID authorizationId,

        /** 入账金额，必须大于 0。 */
        @NotNull @DecimalMin(value = "0.00", inclusive = false)
        BigDecimal amount,

        /** ISO 4217 三位大写货币代码；真实代码识别由 HTTP adapter 的 Currency 转换完成。 */
        @NotBlank @Pattern(regexp = "[A-Z]{3}")
        String currency
) {
}
