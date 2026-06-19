package com.minicard.transaction.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.minicard.authorization.domain.Money;
import lombok.Getter;
import lombok.experimental.Accessors;

/**
 * 持卡人可见的卡交易流水(card transaction)。
 *
 * <p>这里的 CardTransaction 不是 Card aggregate 的子对象，而是发卡行业常见的“卡交易”概念：
 * 一笔消费入账后，后续 refund/reversal/dispute 会继续围绕这条交易生命周期展开。
 * 它也不是 ledger entry；double-entry ledger 可以在后续阶段基于这个业务事实再补。</p>
 */
@Getter
@Accessors(fluent = true)
public final class CardTransaction {

    private final UUID id;
    private final String networkTransactionId;
    private final UUID authorizationId;
    private final String cardId;
    private final UUID creditAccountId;
    private final Money amount;
    private CardTransactionStatus status;
    private final Instant presentmentReceivedAt;
    private Instant postedAt;
    private final Instant createdAt;
    private Instant updatedAt;

    private CardTransaction(
            UUID id,
            String networkTransactionId,
            UUID authorizationId,
            String cardId,
            UUID creditAccountId,
            Money amount,
            CardTransactionStatus status,
            Instant presentmentReceivedAt,
            Instant postedAt,
            Instant createdAt,
            Instant updatedAt
    ) {
        this.id = Objects.requireNonNull(id);
        this.networkTransactionId = requireText(networkTransactionId, "networkTransactionId");
        this.authorizationId = Objects.requireNonNull(authorizationId);
        this.cardId = requireText(cardId, "cardId");
        this.creditAccountId = Objects.requireNonNull(creditAccountId);
        this.amount = Objects.requireNonNull(amount);
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("transaction amount must be greater than zero");
        }
        this.status = Objects.requireNonNull(status);
        this.presentmentReceivedAt = Objects.requireNonNull(presentmentReceivedAt);
        this.postedAt = postedAt;
        this.createdAt = Objects.requireNonNull(createdAt);
        this.updatedAt = Objects.requireNonNull(updatedAt);
        validateState();
    }

    public static CardTransaction pending(
            String networkTransactionId,
            UUID authorizationId,
            String cardId,
            UUID creditAccountId,
            Money amount,
            Instant receivedAt
    ) {
        // id 在 domain factory 生成；networkTransactionId 是外部网络/clearing 侧幂等键。
        return new CardTransaction(
                UUID.randomUUID(),
                networkTransactionId,
                authorizationId,
                cardId,
                creditAccountId,
                amount,
                CardTransactionStatus.PENDING,
                receivedAt,
                null,
                receivedAt,
                receivedAt
        );
    }

    public static CardTransaction restore(
            UUID id,
            String networkTransactionId,
            UUID authorizationId,
            String cardId,
            UUID creditAccountId,
            Money amount,
            CardTransactionStatus status,
            Instant presentmentReceivedAt,
            Instant postedAt,
            Instant createdAt,
            Instant updatedAt
    ) {
        return new CardTransaction(
                id,
                networkTransactionId,
                authorizationId,
                cardId,
                creditAccountId,
                amount,
                status,
                presentmentReceivedAt,
                postedAt,
                createdAt,
                updatedAt
        );
    }

    public void markPosted(Instant postingTime) {
        // PENDING -> POSTED 发生在账户 postedBalance 和 authorization 状态更新之后的同一事务内。
        if (status != CardTransactionStatus.PENDING) {
            throw new IllegalStateException("cannot post card transaction in status " + status);
        }
        postedAt = Objects.requireNonNull(postingTime);
        updatedAt = postingTime;
        status = CardTransactionStatus.POSTED;
    }

    public boolean samePresentment(
            UUID expectedAuthorizationId,
            Money expectedAmount
    ) {
        // networkTransactionId 已经由 repository 唯一索引锁定；这里校验 duplicate retry 的业务内容一致。
        return authorizationId.equals(expectedAuthorizationId)
                && amount.equals(expectedAmount);
    }

    private void validateState() {
        if (status == CardTransactionStatus.PENDING && postedAt != null) {
            throw new IllegalArgumentException("pending transaction cannot have postedAt");
        }
        if (status == CardTransactionStatus.POSTED && postedAt == null) {
            throw new IllegalArgumentException("posted transaction requires postedAt");
        }
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
