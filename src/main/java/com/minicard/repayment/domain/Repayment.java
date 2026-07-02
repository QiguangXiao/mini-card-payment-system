package com.minicard.repayment.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.minicard.shared.domain.Money;
import com.minicard.repayment.domain.event.RepaymentDomainEvent;
import com.minicard.repayment.domain.event.RepaymentReceivedDomainEvent;
import lombok.Getter;
import lombok.experimental.Accessors;

/**
 * 还款 aggregate root，表达一次客户还款从 idempotency claim 到成功入账的生命周期。
 *
 * <p>关键词：还款聚合, 幂等 claim, 入账事件, repayment aggregate,
 * idempotency claim, repayment received, 入金集約(にゅうきんしゅうやく),
 * 支払い済み(しはらいずみ)。</p>
 *
 * <p>它不负责直接修改 statement/account；这些跨 aggregate 协作由 RepaymentService 在一个
 * transaction boundary 内完成。Repayment 自己负责记录“这笔还款已收到”这个业务事实。</p>
 */
// Repayment 只开放 getter；状态必须通过 markReceived 推进，避免外部直接 setStatus(RECEIVED)。
// fluent getter 保持 repayment.status() 这种 record-like 风格，读起来比 getStatus() 更贴近现有 domain。
@Getter
@Accessors(fluent = true)
public final class Repayment {

    /** Repayment 主键；代表一次客户还款请求在系统内的生命周期。 */
    private final UUID id;
    /** API idempotency key；同一次还款 retry 必须复用它，避免重复入账。 */
    private final String idempotencyKey;
    /** 请求体指纹；同 key 不同 amount/statementId 会被识别成 idempotency conflict。 */
    private final String requestFingerprint;
    /** 本次还款要抵扣的 statement id。 */
    private final UUID statementId;
    /** statement 对应的 credit account id；markReceived 时从账单侧补齐。 */
    private UUID creditAccountId;
    /** 还款金额；金额保持正数，减少余额的方向由服务编排和 LedgerDirection 表达。 */
    private final Money amount;
    /** 还款状态；PENDING 表示 claim 已建立，RECEIVED 表示业务入账完成。 */
    private RepaymentStatus status;
    /** 还款成功入账时间；PENDING 时为空。 */
    private Instant receivedAt;
    /** repayment 记录创建时间。 */
    private final Instant createdAt;
    /** 最近一次状态变化时间。 */
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
        // PENDING 阶段还没有 account id；用 Optional 让调用方显式处理这个生命周期差异。
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
