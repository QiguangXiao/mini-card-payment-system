package com.minicard.authorization.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class Authorization {

    private final UUID id;
    private final String requestFingerprint;
    private final String cardId;
    private final Money requestedAmount;
    private AuthorizationStatus status;
    private AuthorizationDeclineReason declineReason;
    private final Instant createdAt;
    private Instant decidedAt;

    private Authorization(
            UUID id,
            String requestFingerprint,
            String cardId,
            Money requestedAmount,
            AuthorizationStatus status,
            AuthorizationDeclineReason declineReason,
            Instant createdAt,
            Instant decidedAt
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
        validateDecisionState();
    }

    public static Authorization request(
            String requestFingerprint,
            String cardId,
            Money requestedAmount,
            Instant createdAt
    ) {
        // An authorization attempt is first recorded as PENDING. Approval is not
        // assumed at construction time because card/account/risk checks must run
        // before a final decision exists.
        return new Authorization(
                UUID.randomUUID(),
                requestFingerprint,
                cardId,
                requestedAmount,
                AuthorizationStatus.PENDING,
                null,
                createdAt,
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
            Instant decidedAt
    ) {
        return new Authorization(
                id,
                requestFingerprint,
                cardId,
                requestedAmount,
                status,
                declineReason,
                createdAt,
                decidedAt
        );
    }

    public void apply(AuthorizationDecision decision, Instant decisionTime) {
        Objects.requireNonNull(decision);
        // Decision objects let domain services express "approve or decline"
        // while the aggregate still owns how that decision mutates state.
        if (decision.approved()) {
            approve(decisionTime);
        } else {
            decline(decision.declineReason(), decisionTime);
        }
    }

    public void approve(Instant decisionTime) {
        // Only one final decision is allowed. This protects the audit trail and
        // prevents accidental transitions such as DECLINED -> APPROVED.
        ensurePending("approve");
        status = AuthorizationStatus.APPROVED;
        declineReason = null;
        decidedAt = Objects.requireNonNull(decisionTime);
    }

    public void decline(AuthorizationDeclineReason reason, Instant decisionTime) {
        // Decline reason is mandatory because operations and support teams need
        // to explain why a transaction failed.
        ensurePending("decline");
        status = AuthorizationStatus.DECLINED;
        declineReason = Objects.requireNonNull(reason);
        decidedAt = Objects.requireNonNull(decisionTime);
    }

    public boolean isPending() {
        return status == AuthorizationStatus.PENDING;
    }

    private void ensurePending(String action) {
        if (status != AuthorizationStatus.PENDING) {
            throw new InvalidAuthorizationStateException(status, action);
        }
    }

    private void validateDecisionState() {
        // This validation also runs when restoring from the database, so corrupt
        // persisted rows cannot silently become valid domain objects.
        if (status == AuthorizationStatus.PENDING && (declineReason != null || decidedAt != null)) {
            throw new IllegalArgumentException("pending authorization cannot have a decision");
        }
        if (status == AuthorizationStatus.APPROVED && (declineReason != null || decidedAt == null)) {
            throw new IllegalArgumentException("approved authorization has invalid decision data");
        }
        if (status == AuthorizationStatus.DECLINED && (declineReason == null || decidedAt == null)) {
            throw new IllegalArgumentException("declined authorization requires decision data");
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
}
