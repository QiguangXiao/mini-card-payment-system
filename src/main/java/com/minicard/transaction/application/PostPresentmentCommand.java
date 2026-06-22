package com.minicard.transaction.application;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.UUID;

import com.minicard.authorization.domain.Money;
import com.minicard.transaction.domain.CardTransaction;

/**
 * Posting use case 的输入 command。
 *
 * <p>名字用 PostPresentment，而不是 Presentment，是为了避免把外部清算记录误建模成 aggregate。
 * Presentment 是输入来源；真正形成并继续演进的领域对象是 CardTransaction。</p>
 */
public record PostPresentmentCommand(
        String networkTransactionId,
        UUID authorizationId,
        BigDecimal amount,
        Currency currency
) {

    // record compact constructor 覆盖所有创建路径，不只覆盖 controller 的 @Valid。
    // 如果测试、scheduler 或未来 Kafka 路径直接 new command，仍不能构造出坏对象。
    public PostPresentmentCommand {
        if (networkTransactionId == null || networkTransactionId.isBlank()) {
            throw new IllegalArgumentException("networkTransactionId must not be blank");
        }
        if (authorizationId == null) {
            throw new IllegalArgumentException("authorizationId must not be null");
        }
        if (amount == null) {
            throw new IllegalArgumentException("amount must not be null");
        }
        if (currency == null) {
            throw new IllegalArgumentException("currency must not be null");
        }
    }

    public Money money() {
        return new Money(amount, currency);
    }

    public boolean matches(CardTransaction transaction) {
        // presentment retry 必须完全指向同一笔授权和金额；否则就是外部 id 重用错误。
        return transaction.samePresentment(authorizationId, money());
    }
}
