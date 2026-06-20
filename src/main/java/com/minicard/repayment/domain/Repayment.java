package com.minicard.repayment.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.minicard.authorization.domain.Money;
import com.minicard.repayment.domain.event.RepaymentDomainEvent;
import com.minicard.repayment.domain.event.RepaymentReceivedDomainEvent;
import lombok.Getter;
import lombok.experimental.Accessors;

/**
 * 还款 aggregate root，表达一次客户还款从 idempotency claim 到成功入账的生命周期。
 *
 * <p>它不负责直接修改 statement/account；这些跨 aggregate 协作由 RepaymentService 在一个
 * transaction boundary 内完成。Repayment 自己负责记录“这笔还款已收到”这个业务事实。</p>
 */
@Getter
@Accessors(fluent = true)
public final class Repayment {

    private final UUID id;
    private final String idempotencyKey;
    private final String requestFingerprint;
    private final UUID statementId;
    private UUID creditAccountId;
    private final Money amount;
    private RepaymentStatus status;
    private Instant receivedAt;
    private final Instant createdAt;
    private Instant updatedAt;
    // Domain event buffer 只存在于内存中；restore 历史还款不会重新发布 repayment.received。
    private final List<RepaymentDomainEvent> domainEvents = new ArrayList<>();

    private Repayment(
            UUID id,
            String idempotencyKey,
            String requestFingerprint,
            UUID statementId,
            UUID creditAccountId,
            Money amount,
            RepaymentStatus status,
            Instant receivedAt,
            Instant createdAt,
            Instant updatedAt
    ) {
        this.id = Objects.requireNonNull(id);
        this.idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
        this.requestFingerprint = requireText(requestFingerprint, "requestFingerprint");
        this.statementId = Objects.requireNonNull(statementId);
        this.creditAccountId = creditAccountId;
        this.amount = Objects.requireNonNull(amount);
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("repayment amount must be positive");
        }
        this.status = Objects.requireNonNull(status);
        this.receivedAt = receivedAt;
        this.createdAt = Objects.requireNonNull(createdAt);
        this.updatedAt = Objects.requireNonNull(updatedAt);
        validateState();
    }

    public static Repayment pending(
            String idempotencyKey,
            String requestFingerprint,
            UUID statementId,
            Money amount,
            Instant createdAt
    ) {
        // id 在 domain factory 生成；idempotencyKey 是 API retry 的业务所有权键。
        return new Repayment(
                UUID.randomUUID(),
                idempotencyKey,
                requestFingerprint,
                statementId,
                null,
                amount,
                RepaymentStatus.PENDING,
                null,
                createdAt,
                createdAt
        );
    }

    public static Repayment restore(
            UUID id,
            String idempotencyKey,
            String requestFingerprint,
            UUID statementId,
            UUID creditAccountId,
            Money amount,
            RepaymentStatus status,
            Instant receivedAt,
            Instant createdAt,
            Instant updatedAt
    ) {
        return new Repayment(
                id,
                idempotencyKey,
                requestFingerprint,
                statementId,
                creditAccountId,
                amount,
                status,
                receivedAt,
                createdAt,
                updatedAt
        );
    }

    public void markReceived(
            UUID creditAccountId,
            Money statementPaidAmount,
            Money statementRemainingAmount,
            Instant receivedAt
    ) {
        // PENDING -> RECEIVED 是 repayment 的核心 state transition。
        // Statement/account 已在 service 中完成同事务更新，这里记录可发布的业务事实。
        if (status != RepaymentStatus.PENDING) {
            throw new IllegalStateException("cannot receive repayment in status " + status);
        }
        this.creditAccountId = Objects.requireNonNull(creditAccountId);
        this.receivedAt = Objects.requireNonNull(receivedAt);
        this.updatedAt = receivedAt;
        this.status = RepaymentStatus.RECEIVED;
        domainEvents.add(new RepaymentReceivedDomainEvent(
                id,
                statementId,
                this.creditAccountId,
                amount,
                statementPaidAmount,
                statementRemainingAmount,
                this.receivedAt
        ));
    }

    public Optional<UUID> creditAccountId() {
        return Optional.ofNullable(creditAccountId);
    }

    public Optional<Instant> receivedAt() {
        return Optional.ofNullable(receivedAt);
    }

    public List<RepaymentDomainEvent> pullDomainEvents() {
        // Application service 在同一 transaction 内保存 aggregate 后调用这里。
        // 返回 copy 并清空，避免同一笔 repayment.received 被重复 append 到 Outbox。
        List<RepaymentDomainEvent> events = List.copyOf(domainEvents);
        domainEvents.clear();
        return events;
    }

    private void validateState() {
        if (status == RepaymentStatus.PENDING
                && (creditAccountId != null || receivedAt != null)) {
            throw new IllegalArgumentException("pending repayment cannot have received data");
        }
        if (status == RepaymentStatus.RECEIVED
                && (creditAccountId == null || receivedAt == null)) {
            throw new IllegalArgumentException("received repayment requires account and receivedAt");
        }
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
