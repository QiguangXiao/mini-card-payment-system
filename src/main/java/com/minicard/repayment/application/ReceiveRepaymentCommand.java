package com.minicard.repayment.application;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Currency;
import java.util.HexFormat;
import java.util.UUID;

import com.minicard.authorization.domain.Money;
import com.minicard.repayment.domain.Repayment;

/**
 * Controller 转入 application layer 的还款命令对象。
 *
 * <p>Fingerprint 用于 idempotency conflict detection：同一个 Idempotency-Key
 * 只能重复提交同一张 statement、同一金额和同一币种。</p>
 */
public record ReceiveRepaymentCommand(
        String idempotencyKey,
        UUID statementId,
        BigDecimal amount,
        Currency currency
) {

    public Money money() {
        return new Money(amount, currency);
    }

    public String requestFingerprint() {
        String canonicalRequest = String.join(
                "|",
                statementId.toString(),
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

    public boolean matches(Repayment repayment) {
        return requestFingerprint().equals(repayment.requestFingerprint());
    }
}
