package com.minicard.risk.domain;

import java.util.Locale;

import com.minicard.shared.domain.Money;

/**
 * 风控评估请求。
 *
 * <p>关键词：风控请求, 商户国家, 跨境交易, risk assessment request,
 * merchant country, cross-border, リスク評価依頼(リスクひょうかいらい),
 * 越境取引(えっきょうとりひき)。</p>
 */
public record RiskAssessmentRequest(
        /** card id。 */
        String cardId,
        /** merchant id。 */
        String merchantId,
        /** 商户国家，ISO-3166 alpha-2。 */
        String merchantCountry,
        /** 持卡人国家，ISO-3166 alpha-2。 */
        String cardholderCountry,
        /** 授权金额。 */
        Money amount
) {

    public RiskAssessmentRequest {
        cardId = requireText(cardId, "cardId");
        merchantId = requireText(merchantId, "merchantId");
        merchantCountry = normalizeCountry(merchantCountry, "merchantCountry");
        cardholderCountry = normalizeCountry(cardholderCountry, "cardholderCountry");
        java.util.Objects.requireNonNull(amount);
    }

    /**
     * 判断是否跨境交易。
     */
    public boolean isCrossBorder() {
        return !merchantCountry.equals(cardholderCountry);
    }

    /**
     * 校验必填文本。
     */
    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }

    /**
     * 统一国家代码为大写 ISO-3166 alpha-2。
     */
    private static String normalizeCountry(String value, String fieldName) {
        String text = requireText(value, fieldName).toUpperCase(Locale.ROOT);
        if (!text.matches("[A-Z]{2}")) {
            throw new IllegalArgumentException(fieldName + " must be ISO-3166 alpha-2");
        }
        return text;
    }
}
