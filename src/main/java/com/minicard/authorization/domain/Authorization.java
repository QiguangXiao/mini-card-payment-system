package com.minicard.authorization.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.minicard.shared.domain.Money;
import com.minicard.authorization.domain.event.AuthorizationApprovedDomainEvent;
import com.minicard.authorization.domain.event.AuthorizationDeclinedDomainEvent;
import com.minicard.authorization.domain.event.AuthorizationDomainEvent;
import com.minicard.authorization.domain.event.AuthorizationExpiredDomainEvent;
import com.minicard.authorization.domain.event.AuthorizationPostedDomainEvent;

/**
 * 授权 aggregate root，表达一笔 card authorization 从 PENDING 到 APPROVED/POSTED/DECLINED/EXPIRED 的生命周期。
 *
 * <p>关键词：授权聚合, 状态转换, 领域事件, authorization aggregate,
 * state transition, domain event, オーソリ集約(オーソリしゅうやく),
 * 状態遷移(じょうたいせんい)。</p>
 *
 * <p>interview重点：状态转换(state transition)放在 domain 内部，service 只能调用
 * approve/decline/expire 这些业务行为，不能绕过 invariant 直接改字段。</p>
 */
public final class Authorization {

    /**
     * Hold duration 是授权保留额度的业务窗口。APPROVED 时会把 expiresAt 持久化，
     * 这样以后策略变化不会偷偷改变历史 authorization 的过期时间。
     */
    private static final Duration HOLD_DURATION = Duration.ofDays(7);

    /** Authorization 主键；本系统内部生命周期都围绕它推进。 */
    private final UUID id;
    /** API 请求指纹；和 idempotency key 配合，用来发现同 key 不同 body 的冲突请求。 */
    private final String requestFingerprint;
    /** 被授权的卡号/卡 id；授权先检查 Card lifecycle，再锁 CreditAccount 预占额度。 */
    private final String cardId;
    /** 本次请求预占的金额；授权金额保持正数，币种由 Money 明确表达。 */
    private final Money requestedAmount;
    /** 授权生命周期状态：PENDING、APPROVED、DECLINED、POSTED、EXPIRED。 */
    private AuthorizationStatus status;
    /** 拒绝原因；只有 DECLINED 状态才应出现，用于 API 返回和风控/额度解释。 */
    private AuthorizationDeclineReason declineReason;
    /** 授权请求进入系统的时间。 */
    private final Instant createdAt;
    /** approve/decline 做出决定的时间；PENDING 时为空。 */
    private Instant decidedAt;
    // expiresAt 是显式 business deadline，不从 decidedAt 动态重算，方便 audit/replay。
    private Instant expiresAt;
    // postedAt 表示 issuer 已收到 presentment 并把交易 posted to account。
    private Instant postedAt;
    // expiredAt 表示 scheduler 实际完成释放的时间，可能晚于 expiresAt；定时任务是 eventual execution。
    private Instant expiredAt;
    // Domain event buffer 只存在于内存中：状态转换在哪里发生，业务事实就在哪里产生。
    // Repository restore 出来的历史对象不会重新发布事件，避免 replay/查询造成重复消息。
    private final List<AuthorizationDomainEvent> domainEvents = new ArrayList<>();

    private Authorization(
            UUID id,
            String requestFingerprint,
            String cardId,
            Money requestedAmount,
            AuthorizationStatus status,
            AuthorizationDeclineReason declineReason,
            Instant createdAt,
            Instant decidedAt,
            Instant expiresAt,
            Instant postedAt,
            Instant expiredAt
    ) {
        this.id = Objects.requireNonNull(id);
        this.requestFingerprint = requireText(requestFingerprint, "requestFingerprint");
        this.cardId = requireText(cardId, "cardId");
        this.requestedAmount = Objects.requireNonNull(requestedAmount);
        if (!requestedAmount.isPositive()) {
            throw new IllegalArgumentException("authorization amount must be greater than zero");
        }
        this.status = Objects.requireNonNull(status);
        this.declineReason = declineReason;
        this.createdAt = Objects.requireNonNull(createdAt);
        this.decidedAt = decidedAt;
        this.expiresAt = expiresAt;
        this.postedAt = postedAt;
        this.expiredAt = expiredAt;
        validateDecisionState();
    }

    public static Authorization request(
            String requestFingerprint,
            String cardId,
            Money requestedAmount,
            Instant createdAt
    ) {
        // request() 是新授权的 factory method：这里生成 UUID，并先进入 PENDING。
        // 是否 APPROVED 要等 card/account/risk 检查后才能决定。
        return new Authorization(
                UUID.randomUUID(),
                requestFingerprint,
                cardId,
                requestedAmount,
                AuthorizationStatus.PENDING,
                null,
                createdAt,
                null,
                null,
                null,
                null
        );
    }

    public static Authorization restore(
            UUID id,
            String requestFingerprint,
            String cardId,
            Money requestedAmount,
            AuthorizationStatus status,
            AuthorizationDeclineReason declineReason,
            Instant createdAt,
            Instant decidedAt,
            Instant expiresAt,
            Instant postedAt,
            Instant expiredAt
    ) {
        return new Authorization(
                id,
                requestFingerprint,
                cardId,
                requestedAmount,
                status,
                declineReason,
                createdAt,
                decidedAt,
                expiresAt,
                postedAt,
                expiredAt
        );
    }

    /**
     * 将一笔待决授权批准为 APPROVED，并记录可发布的 authorization.approved 领域事件。
     */
    public void approve(Instant decisionTime) {
        // ensurePending() 防止重复决策，保护 audit trail，并禁止 DECLINED -> APPROVED 这类非法跳转。
        ensurePending("approve");
        status = AuthorizationStatus.APPROVED;
        declineReason = null;
        decidedAt = Objects.requireNonNull(decisionTime);
        expiresAt = decisionTime.plus(HOLD_DURATION);
        postedAt = null;
        expiredAt = null;
        // APPROVED 事件由 aggregate 自己记录，不让 application service 反向推断 domain fact。
        domainEvents.add(new AuthorizationApprovedDomainEvent(
                id,
                cardId,
                requestedAmount,
                decidedAt,
                expiresAt
        ));
    }

    /**
     * 将一笔待决授权拒绝为 DECLINED，并保留拒绝原因供 API、审计和通知使用。
     */
    public void decline(AuthorizationDeclineReason reason, Instant decisionTime) {
        // decline reason 必填，后续 API、客服和审计都需要知道失败原因。
        ensurePending("decline");
        status = AuthorizationStatus.DECLINED;
        declineReason = Objects.requireNonNull(reason);
        decidedAt = Objects.requireNonNull(decisionTime);
        expiresAt = null;
        postedAt = null;
        expiredAt = null;
        // DECLINED 是一次真实业务决策；事件跟随状态转换生成，符合 DDD domain event 语义。
        domainEvents.add(new AuthorizationDeclinedDomainEvent(
                id,
                cardId,
                requestedAmount,
                declineReason,
                decidedAt
        ));
    }

    /**
     * 将已批准但未入账的授权过期，表达额度 hold 已被释放的业务事实。
     */
    public void expire(Instant expiryTime) {
        // expire() 只允许 APPROVED -> EXPIRED。释放额度前后要保持 authorization 状态可追踪。
        ensureApproved("expire");
        Instant actualExpiryTime = Objects.requireNonNull(expiryTime);
        if (actualExpiryTime.isBefore(expiresAt)) {
            throw new IllegalArgumentException("authorization cannot expire before expiresAt");
        }
        status = AuthorizationStatus.EXPIRED;
        expiredAt = actualExpiryTime;
        // EXPIRED 事件记录“额度 hold 已释放对应的授权已过期”，后续由 Outbox 可靠发布。
        domainEvents.add(new AuthorizationExpiredDomainEvent(
                id,
                cardId,
                requestedAmount,
                expiresAt,
                expiredAt
        ));
    }

    /**
     * 将已批准授权标记为 POSTED，表示 presentment 已到达并进入入账流程。
     */
    public void post(Instant postingTime) {
        // POSTED 是 issuer 视角：presentment 到达后，这笔授权从“占额度”变成“已入账交易”。
        ensureApproved("post");
        Instant actualPostingTime = Objects.requireNonNull(postingTime);
        if (actualPostingTime.isAfter(expiresAt)) {
            throw new IllegalStateException("authorization cannot be posted after expiresAt");
        }
        status = AuthorizationStatus.POSTED;
        postedAt = actualPostingTime;
        expiredAt = null;
        // POSTED 事件同样由 aggregate 产生，service 只负责事务内持久化和交给 Outbox。
        domainEvents.add(new AuthorizationPostedDomainEvent(
                id,
                cardId,
                requestedAmount,
                postedAt
        ));
    }

    /**
     * 判断授权是否仍处于可决策状态，主要用于 idempotency winner 的后续处理。
     */
    public boolean isPending() {
        return status == AuthorizationStatus.PENDING;
    }

    /**
     * 取出并清空本次状态转换产生的领域事件，交给 application service 写入 Outbox。
     */
    public List<AuthorizationDomainEvent> pullDomainEvents() {
        // Application service 在同一 transaction 内保存 aggregate 后调用这里。
        // 返回 copy 并清空，避免同一个对象被重复 append 到 Outbox。
        List<AuthorizationDomainEvent> events = List.copyOf(domainEvents);
        domainEvents.clear();
        return events;
    }

    /**
     * 保护只能从 PENDING 发起的状态转换，避免重复决策或非法回退。
     */
    private void ensurePending(String action) {
        if (status != AuthorizationStatus.PENDING) {
            throw new IllegalStateException("cannot " + action + " authorization in status " + status);
        }
    }

    /**
     * 保护只能从 APPROVED 发起的后续动作，例如 posting 或 expiry。
     */
    private void ensureApproved(String action) {
        if (status != AuthorizationStatus.APPROVED) {
            throw new IllegalStateException("cannot " + action + " authorization in status " + status);
        }
    }

    /**
     * 校验状态和时间/原因字段是否成组出现，防止 DB restore 出半截生命周期对象。
     */
    private void validateDecisionState() {
        // restore() 从 DB 还原时也会跑这段 validation，避免脏数据绕过 domain invariant。
        if (status == AuthorizationStatus.PENDING
                && (declineReason != null || decidedAt != null
                || expiresAt != null || postedAt != null || expiredAt != null)) {
            throw new IllegalArgumentException("pending authorization cannot have a decision");
        }
        if (status == AuthorizationStatus.APPROVED
                && (declineReason != null || decidedAt == null
                || expiresAt == null || postedAt != null || expiredAt != null)) {
            throw new IllegalArgumentException("approved authorization has invalid decision data");
        }
        if (status == AuthorizationStatus.POSTED
                && (declineReason != null || decidedAt == null
                || expiresAt == null || postedAt == null || expiredAt != null
                || postedAt.isAfter(expiresAt))) {
            throw new IllegalArgumentException("posted authorization has invalid posting data");
        }
        if (status == AuthorizationStatus.DECLINED
                && (declineReason == null || decidedAt == null
                || expiresAt != null || postedAt != null || expiredAt != null)) {
            throw new IllegalArgumentException("declined authorization requires decision data");
        }
        if (status == AuthorizationStatus.EXPIRED
                && (declineReason != null || decidedAt == null
                || expiresAt == null || postedAt != null || expiredAt == null
                || expiredAt.isBefore(expiresAt))) {
            throw new IllegalArgumentException("expired authorization has invalid expiry data");
        }
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }

    public UUID id() {
        return id;
    }

    public String requestFingerprint() {
        return requestFingerprint;
    }

    public String cardId() {
        return cardId;
    }

    public Money requestedAmount() {
        return requestedAmount;
    }

    public AuthorizationStatus status() {
        return status;
    }

    public Optional<AuthorizationDeclineReason> declineReason() {
        // Optional 用在返回值上表达“可能没有”，强迫调用方显式处理。
        // 如果直接返回 nullable enum，调用方很容易漏判，在 approved/pending 状态上触发 NPE。
        return Optional.ofNullable(declineReason);
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Optional<Instant> decidedAt() {
        return Optional.ofNullable(decidedAt);
    }

    public Optional<Instant> expiresAt() {
        return Optional.ofNullable(expiresAt);
    }

    public Optional<Instant> postedAt() {
        return Optional.ofNullable(postedAt);
    }

    public Optional<Instant> expiredAt() {
        return Optional.ofNullable(expiredAt);
    }
}
