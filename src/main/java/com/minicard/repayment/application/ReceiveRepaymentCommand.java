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
        // Money 把 BigDecimal 和 Currency 绑定；如果 service 里传散参数，币种错配更难发现。
        return new Money(amount, currency);
    }

    public String requestFingerprint() {
        // canonical request 必须字段顺序稳定；如果用 JSON toString 或 Map iteration，digest 可能随实现变化。
        String canonicalRequest = String.join(
                "|",
                statementId.toString(),
                amount.stripTrailingZeros().toPlainString(),
                currency.getCurrencyCode()
        );
        try {
            // SHA-256 是 JDK 标准算法；NoSuchAlgorithmException 理论上不应发生，但 checked exception 必须处理。
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
