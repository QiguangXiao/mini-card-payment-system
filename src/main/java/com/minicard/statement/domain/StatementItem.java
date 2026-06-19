package com.minicard.statement.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.minicard.authorization.domain.Money;
import lombok.Getter;
import lombok.experimental.Accessors;

/**
 * 账单行项目(statement item)，是账单生成时对 posted CardTransaction 的历史快照。
 *
 * <p>面试重点：账单不是每次查询时临时 SUM 交易表。生成账单时把交易事实 snapshot 下来，
 * 才能解释 audit trail、账单不可随意变动，以及后续 refund 在账单前后处理不同。</p>
 */
@Getter
@Accessors(fluent = true)
public final class StatementItem {

    private final UUID id;
    private final UUID statementId;
    private final UUID cardTransactionId;
    private final String networkTransactionId;
    private final UUID authorizationId;
    private final String cardId;
    private final Money amount;
    private final Instant postedAt;
    private final Instant createdAt;

    private StatementItem(
            UUID id,
            UUID statementId,
            UUID cardTransactionId,
            String networkTransactionId,
            UUID authorizationId,
            String cardId,
            Money amount,
            Instant postedAt,
            Instant createdAt
    ) {
        this.id = Objects.requireNonNull(id);
        this.statementId = Objects.requireNonNull(statementId);
        this.cardTransactionId = Objects.requireNonNull(cardTransactionId);
        this.networkTransactionId = requireText(networkTransactionId, "networkTransactionId");
        this.authorizationId = Objects.requireNonNull(authorizationId);
        this.cardId = requireText(cardId, "cardId");
        this.amount = Objects.requireNonNull(amount);
        this.postedAt = Objects.requireNonNull(postedAt);
        this.createdAt = Objects.requireNonNull(createdAt);
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("statement item amount must be positive");
        }
    }

    public static StatementItem snapshot(
            UUID statementId,
            StatementTransaction transaction,
            Instant createdAt
    ) {
        // item id 也在 domain factory 生成，表示“这条账单快照行”自己的身份，
        // 不复用 cardTransactionId，避免把源交易和账单行生命周期混在一起。
        return restore(
                UUID.randomUUID(),
                statementId,
                transaction.cardTransactionId(),
                transaction.networkTransactionId(),
                transaction.authorizationId(),
                transaction.cardId(),
                transaction.amount(),
                transaction.postedAt(),
                createdAt
        );
    }

    public static StatementItem restore(
            UUID id,
            UUID statementId,
            UUID cardTransactionId,
            String networkTransactionId,
            UUID authorizationId,
            String cardId,
            Money amount,
            Instant postedAt,
            Instant createdAt
    ) {
        return new StatementItem(
                id,
                statementId,
                cardTransactionId,
                networkTransactionId,
                authorizationId,
                cardId,
                amount,
                postedAt,
                createdAt
        );
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
