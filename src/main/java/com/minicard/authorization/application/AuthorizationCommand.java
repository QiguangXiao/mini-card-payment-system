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
        return new Money(amount, currency);
    }

    public RiskAssessmentRequest toRiskAssessmentRequest() {
        return new RiskAssessmentRequest(
                cardId,
                merchantId,
                merchantCountry,
                cardholderCountry,
                requestedAmount()
        );
    }

    public String requestFingerprint() {
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
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(
                    canonicalRequest.getBytes(StandardCharsets.UTF_8)
            ));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    public boolean matches(Authorization authorization) {
        return requestFingerprint().equals(authorization.requestFingerprint());
    }

    private static String normalizeCountry(String country) {
        return country.toUpperCase(Locale.ROOT);
    }
}
