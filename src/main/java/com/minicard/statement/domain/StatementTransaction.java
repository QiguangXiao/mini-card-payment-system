package com.minicard.statement.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.minicard.authorization.domain.Money;

/**
 * 生成账单时从 CardTransaction 复制出来的候选交易事实。
 *
 * <p>Statement domain 不直接依赖 transaction package 的 aggregate class，只接收
 * 账单真正需要的字段。这样保持 bounded context 边界清楚，也让账单快照(snapshot)
 * 的含义更明显。</p>
 */
public record StatementTransaction(
        UUID cardTransactionId,
        String networkTransactionId,
        UUID authorizationId,
        String cardId,
        Money amount,
        Instant postedAt
) {

    public StatementTransaction {
        Objects.requireNonNull(cardTransactionId, "cardTransactionId must not be null");
        requireText(networkTransactionId, "networkTransactionId");
        Objects.requireNonNull(authorizationId, "authorizationId must not be null");
        requireText(cardId, "cardId");
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(postedAt, "postedAt must not be null");
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("statement transaction amount must be positive");
        }
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
