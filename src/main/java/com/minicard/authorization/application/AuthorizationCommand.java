package com.minicard.authorization.application;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Currency;
import java.util.HexFormat;
import java.util.Locale;

import com.minicard.authorization.domain.Authorization;
import com.minicard.authorization.domain.Money;
import com.minicard.risk.domain.RiskAssessmentRequest;

/**
 * Controller 转入 application layer 的授权命令对象。
 *
 * <p>它把 HTTP DTO 和领域模型隔开，同时集中处理 request fingerprint。
 * interview重点：fingerprint 是 idempotency conflict detection，不是安全认证。</p>
 */
public record AuthorizationCommand(
        String idempotencyKey,
        String cardId,
        BigDecimal amount,
        Currency currency,
        String merchantId,
        String merchantCountry,
        String cardholderCountry
) {

    public Money requestedAmount() {
        // Money value object 把金额和币种绑定在一起，避免 service 里到处传 BigDecimal + Currency。
        return new Money(amount, currency);
    }

    public RiskAssessmentRequest toRiskAssessmentRequest() {
        // RiskAssessmentRequest 是给风控(risk)模块的输入模型，避免风控直接依赖 HTTP DTO。
        return new RiskAssessmentRequest(
                cardId,
                merchantId,
                merchantCountry,
                cardholderCountry,
                requestedAmount()
        );
    }

    public String requestFingerprint() {
        // canonical request 表示“同一个业务请求”的稳定字符串。
        // 同一个 Idempotency-Key 只能重复提交完全相同的 canonical request。
        String canonicalRequest = String.join(
                "|",
                cardId,
                merchantId,
                normalizeCountry(merchantCountry),
                normalizeCountry(cardholderCountry),
                amount.stripTrailingZeros().toPlainString(),
                currency.getCurrencyCode()
        );
        try {
            // SHA-256 fingerprint 用来快速比较请求是否相同；它不是安全认证，只是幂等冲突检测。
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(
                    canonicalRequest.getBytes(StandardCharsets.UTF_8)
            ));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    public boolean matches(Authorization authorization) {
        // Retry 请求必须和已保存 authorization 的 fingerprint 一致，否则说明复用了错误的幂等键。
        return requestFingerprint().equals(authorization.requestFingerprint());
    }

    private static String normalizeCountry(String country) {
        // Locale.ROOT 避免土耳其语等本地化大小写规则影响 fingerprint。
        // 如果用默认 Locale，同一请求在不同 JVM 区域设置下可能算出不同 digest。
        return country.toUpperCase(Locale.ROOT);
    }
}
