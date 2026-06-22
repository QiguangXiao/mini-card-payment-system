package com.minicard.authorization.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 创建授权的 HTTP request DTO。
 *
 * <p>DTO 只表达 API contract 和输入校验；金额/币种进入 application 后会被转换成
 * Money value object，避免 controller 承担业务语义。</p>
 *
 * <p>Java record 适合 request DTO：字段不可变、构造函数和 accessor 自动生成，Jackson 也能直接绑定。
 * 如果用可变 POJO + setter，后续代码可能意外修改入参，测试也更难判断请求对象何时变化。</p>
 */
public record CreateAuthorizationRequest(
        // Bean Validation 是 API contract 的第一道防线；domain 仍会保留 invariant，保护 scheduler/test/restore 路径。
        @NotBlank @Size(max = 100) String cardId,
        // BigDecimal 搭配 @Digits 明确数据库 DECIMAL(19,2) 的边界。
        // 如果让任意 scale 进入，写库时才四舍五入或截断，金额问题会更隐蔽。
        @NotNull @DecimalMin(value = "0.01") @Digits(integer = 17, fraction = 2) BigDecimal amount,
        // 正则先保证 ISO 4217 形状；Currency.getInstance 负责识别是否是 JDK 支持的真实币种。
        @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
        @NotBlank @Size(max = 100) String merchantId,
        @NotBlank @Pattern(regexp = "[A-Z]{2}") String merchantCountry,
        @NotBlank @Pattern(regexp = "[A-Z]{2}") String cardholderCountry
) {
}
