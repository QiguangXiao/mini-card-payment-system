package com.minicard.transaction.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.minicard.shared.domain.Money;
import com.minicard.transaction.domain.event.CardTransactionDomainEvent;
import com.minicard.transaction.domain.event.CardTransactionPostedDomainEvent;
import lombok.Getter;
import lombok.experimental.Accessors;

/**
 * 持卡人可见的卡交易流水(card transaction)。
 *
 * <p>关键词：卡交易聚合, presentment, 账单分配, card transaction aggregate,
 * posted transaction, statement assignment, 取引集約(とりひきしゅうやく),
 * 請求明細への紐づけ(せいきゅうめいさいへのひもづけ)。</p>
 *
 * <p>这里的 CardTransaction 不是 Card aggregate 的子对象，而是发卡行业常见的“卡交易”概念：
 * 一笔消费入账后，后续 refund/reversal/dispute 会继续围绕这条交易生命周期展开。
 * 它也不是 ledger entry；double-entry ledger 可以在后续阶段基于这个业务事实再补。</p>
 */
// CardTransaction 有生命周期行为，所以只用 Lombok getter，不生成 setter。
// 如果用 @Data，外部代码可以直接改 statementId/status，绕过 markPosted/assignToStatement 的保护。
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
    private CardTransactionBillingStatus billingStatus;
    private final Instant presentmentReceivedAt;
    private Instant postedAt;
    private UUID statementId;
    private Instant statementAssignedAt;
    private final Instant createdAt;
    private Instant updatedAt;
    // Domain event buffer 只存在于内存中；restore 出来的历史对象不会重新发布事件。
    private final List<CardTransactionDomainEvent> domainEvents = new ArrayList<>();

    private CardTransaction(
            UUID id,
            String networkTransactionId,
            UUID authorizationId,
            String cardId,
            UUID creditAccountId,
            Money amount,
            CardTransactionStatus status,
            CardTransactionBillingStatus billingStatus,
            Instant presentmentReceivedAt,
            Instant postedAt,
            UUID statementId,
            Instant statementAssignedAt,
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
        this.billingStatus = Objects.requireNonNull(billingStatus);
        this.presentmentReceivedAt = Objects.requireNonNull(presentmentReceivedAt);
        this.postedAt = postedAt;
        this.statementId = statementId;
        this.statementAssignedAt = statementAssignedAt;
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
                CardTransactionBillingStatus.UNBILLED,
                receivedAt,
                null,
                null,
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
            CardTransactionBillingStatus billingStatus,
            Instant presentmentReceivedAt,
            Instant postedAt,
            UUID statementId,
            Instant statementAssignedAt,
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
                billingStatus,
                presentmentReceivedAt,
                postedAt,
                statementId,
                statementAssignedAt,
                createdAt,
                updatedAt
        );
    }

    public void markPosted(Instant postingTime) {
        // PENDING -> POSTED 是用户可见交易入账事实。
        // 因为 Notification 未来可能是独立微服务，后续通知应消费这个 CardTransaction event，
        // 而不是依赖 Authorization 的内部生命周期事件。
        if (status != CardTransactionStatus.PENDING) {
            throw new IllegalStateException("cannot post card transaction in status " + status);
        }
        postedAt = Objects.requireNonNull(postingTime);
        updatedAt = postingTime;
        status = CardTransactionStatus.POSTED;
        domainEvents.add(new CardTransactionPostedDomainEvent(
                id,
                networkTransactionId,
                authorizationId,
                cardId,
                creditAccountId,
                amount,
                postedAt
        ));
    }

    public void assignToStatement(UUID statementId, Instant assignedAt) {
        // POSTED -> billed-to-statement 不是新的交易状态，而是账单归属关系。
        // 这里放在 aggregate 内部，防止同一笔交易被两个 statement 重复收录。
        if (status != CardTransactionStatus.POSTED) {
            throw new IllegalStateException("only posted card transactions can be assigned to statement");
        }
        if (this.statementId != null) {
            throw new IllegalStateException("card transaction is already assigned to a statement");
        }
        if (billingStatus == CardTransactionBillingStatus.BILLED) {
            // billingStatus 是显式 billed marker；没有这个 guard，脏数据可能让同一交易被重复写入 statement line。
            throw new IllegalStateException("card transaction is already billed");
        }
        this.statementId = Objects.requireNonNull(statementId);
        this.statementAssignedAt = Objects.requireNonNull(assignedAt);
        this.billingStatus = CardTransactionBillingStatus.BILLED;
        updatedAt = assignedAt;
    }

    public Optional<UUID> statementId() {
        // Domain 返回 Optional 表达“未出账时没有 statementId”；API DTO 再把它转成 nullable JSON 字段。
        return Optional.ofNullable(statementId);
    }

    public Optional<Instant> statementAssignedAt() {
        return Optional.ofNullable(statementAssignedAt);
    }

    public List<CardTransactionDomainEvent> pullDomainEvents() {
        // Application service 在同一 transaction 内保存 aggregate 后调用这里。
        // 返回 copy 并清空，避免同一个对象被重复 append 到 Outbox。
        List<CardTransactionDomainEvent> events = List.copyOf(domainEvents);
        domainEvents.clear();
        return events;
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
        if (status == CardTransactionStatus.PENDING
                && (statementId != null || statementAssignedAt != null)) {
            throw new IllegalArgumentException("pending transaction cannot be assigned to statement");
        }
        if (status == CardTransactionStatus.PENDING
                && billingStatus == CardTransactionBillingStatus.BILLED) {
            throw new IllegalArgumentException("pending transaction cannot be billed");
        }
        if ((statementId == null) != (statementAssignedAt == null)) {
            throw new IllegalArgumentException("statement assignment requires both id and timestamp");
        }
        if (billingStatus == CardTransactionBillingStatus.BILLED && statementId == null) {
            throw new IllegalArgumentException("billed transaction requires statement assignment");
        }
        if (billingStatus == CardTransactionBillingStatus.UNBILLED && statementId != null) {
            throw new IllegalArgumentException("statement assignment requires billed status");
        }
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
