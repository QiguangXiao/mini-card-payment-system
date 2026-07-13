package com.minicard.statement.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.minicard.shared.domain.Money;

/**
 * 生成 statement line 时需要的消费事实。
 *
 * <p>关键词：账单明细来源, 卡交易, statement line source,
 * card transaction, 請求明細元(せいきゅうめいさいもと)。</p>
 *
 * <p>CardTransaction 是本项目已经完成 posting 的交易事实；StatementLine 在出账事务内
 * 把它冻结成历史快照。生产系统可再关联独立 accounting journal，但不应让可选异步投影
 * 成为本项目账单生成的 liveness 前置条件。</p>
 */
public record StatementLineSource(
        UUID cardTransactionId,
        String networkTransactionId,
        UUID authorizationId,
        String cardId,
        Money amount,
        Instant postedAt
) {

    public StatementLineSource {
        Objects.requireNonNull(cardTransactionId, "cardTransactionId must not be null");
        requireText(networkTransactionId, "networkTransactionId");
        Objects.requireNonNull(authorizationId, "authorizationId must not be null");
        requireText(cardId, "cardId");
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(postedAt, "postedAt must not be null");
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("statement line source amount must be positive");
        }
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
