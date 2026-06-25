package com.minicard.statement.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.minicard.authorization.domain.Money;

/**
 * 生成 statement line 时需要的消费事实。
 *
 * <p>关键词：账单明细来源, 卡交易, 账本分录, statement line source,
 * card transaction, ledger entry, 請求明細元(せいきゅうめいさいもと),
 * 取引と仕訳(とりひきとしわけ)。</p>
 *
 * <p>CardTransaction 是用户可见消费明细；LedgerEntry 是内部入账依据。
 * StatementLine 同时引用两者，避免只靠交易流水出账、却无法和账务分录对齐。</p>
 */
public record StatementLineSource(
        UUID cardTransactionId,
        UUID ledgerEntryId,
        String networkTransactionId,
        UUID authorizationId,
        String cardId,
        Money amount,
        Instant postedAt
) {

    public StatementLineSource {
        Objects.requireNonNull(cardTransactionId, "cardTransactionId must not be null");
        Objects.requireNonNull(ledgerEntryId, "ledgerEntryId must not be null");
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
