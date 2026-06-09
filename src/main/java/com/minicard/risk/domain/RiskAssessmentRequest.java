package com.minicard.risk.domain;

import java.util.Locale;

import com.minicard.authorization.domain.Money;

public record RiskAssessmentRequest(
        String cardId,
        String merchantId,
        String merchantCountry,
        String cardholderCountry,
        Money amount
) {

    public RiskAssessmentRequest {
        cardId = requireText(cardId, "cardId");
        merchantId = requireText(merchantId, "merchantId");
        merchantCountry = normalizeCountry(merchantCountry, "merchantCountry");
        cardholderCountry = normalizeCountry(cardholderCountry, "cardholderCountry");
        java.util.Objects.requireNonNull(amount);
    }

    public boolean isCrossBorder() {
        return !merchantCountry.equals(cardholderCountry);
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }

    private static String normalizeCountry(String value, String fieldName) {
        String text = requireText(value, fieldName).toUpperCase(Locale.ROOT);
        if (!text.matches("[A-Z]{2}")) {
            throw new IllegalArgumentException(fieldName + " must be ISO-3166 alpha-2");
        }
        return text;
    }
}
