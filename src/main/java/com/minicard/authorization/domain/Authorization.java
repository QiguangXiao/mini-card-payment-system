package com.minicard.authorization.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class Authorization {

    /**
     * Hold duration 是授权保留额度的业务窗口。APPROVED 时会把 expiresAt 持久化，
     * 这样以后策略变化不会偷偷改变历史 authorization 的过期时间。
     */
    private static final Duration HOLD_DURATION = Duration.ofDays(7);

    private final UUID id;
    private final String requestFingerprint;
    private final String cardId;
    private final Money requestedAmount;
    private AuthorizationStatus status;
    private AuthorizationDeclineReason declineReason;
    private final Instant createdAt;
    private Instant decidedAt;
    // expiresAt 是显式 business deadline，不从 decidedAt 动态重算，方便 audit/replay。
    private Instant expiresAt;
    // expiredAt 表示 scheduler 实际完成释放的时间，可能晚于 expiresAt；定时任务是 eventual execution。
    private Instant expiredAt;

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
                expiredAt
        );
    }

    public void apply(AuthorizationDecision decision, Instant decisionTime) {
        Objects.requireNonNull(decision);
        // AuthorizationDecision 表达“批准或拒绝”，但真正修改状态的规则仍由 aggregate 维护。
        if (decision.approved()) {
            approve(decisionTime);
        } else {
            decline(decision.declineReason(), decisionTime);
        }
    }

    public void approve(Instant decisionTime) {
        // ensurePending() 防止重复决策，保护 audit trail，并禁止 DECLINED -> APPROVED 这类非法跳转。
        ensurePending("approve");
        status = AuthorizationStatus.APPROVED;
        declineReason = null;
        decidedAt = Objects.requireNonNull(decisionTime);
        expiresAt = decisionTime.plus(HOLD_DURATION);
        expiredAt = null;
    }

    public void decline(AuthorizationDeclineReason reason, Instant decisionTime) {
        // decline reason 必填，后续 API、客服和审计都需要知道失败原因。
        ensurePending("decline");
        status = AuthorizationStatus.DECLINED;
        declineReason = Objects.requireNonNull(reason);
        decidedAt = Objects.requireNonNull(decisionTime);
        expiresAt = null;
        expiredAt = null;
    }

    public void expire(Instant expiryTime) {
        // expire() 只允许 APPROVED -> EXPIRED。释放额度前后要保持 authorization 状态可追踪。
        ensureApproved("expire");
        Instant actualExpiryTime = Objects.requireNonNull(expiryTime);
        if (actualExpiryTime.isBefore(expiresAt)) {
            throw new IllegalArgumentException("authorization cannot expire before expiresAt");
        }
        status = AuthorizationStatus.EXPIRED;
        expiredAt = actualExpiryTime;
    }

    public boolean isPending() {
        return status == AuthorizationStatus.PENDING;
    }

    private void ensurePending(String action) {
        if (status != AuthorizationStatus.PENDING) {
            throw new InvalidAuthorizationStateException(status, action);
        }
    }

    private void ensureApproved(String action) {
        if (status != AuthorizationStatus.APPROVED) {
            throw new InvalidAuthorizationStateException(status, action);
        }
    }

    private void validateDecisionState() {
        // restore() 从 DB 还原时也会跑这段 validation，避免脏数据绕过 domain invariant。
        if (status == AuthorizationStatus.PENDING
                && (declineReason != null || decidedAt != null
                || expiresAt != null || expiredAt != null)) {
            throw new IllegalArgumentException("pending authorization cannot have a decision");
        }
        if (status == AuthorizationStatus.APPROVED
                && (declineReason != null || decidedAt == null
                || expiresAt == null || expiredAt != null)) {
            throw new IllegalArgumentException("approved authorization has invalid decision data");
        }
        if (status == AuthorizationStatus.DECLINED
                && (declineReason == null || decidedAt == null
                || expiresAt != null || expiredAt != null)) {
            throw new IllegalArgumentException("declined authorization requires decision data");
        }
        if (status == AuthorizationStatus.EXPIRED
                && (declineReason != null || decidedAt == null
                || expiresAt == null || expiredAt == null
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

    public Optional<Instant> expiredAt() {
        return Optional.ofNullable(expiredAt);
    }
}
