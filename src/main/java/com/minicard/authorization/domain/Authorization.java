package com.minicard.authorization.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class Authorization {

    private final UUID id;
    private final String cardId;
    private final Money requestedAmount;
    private AuthorizationStatus status;
    private AuthorizationDeclineReason declineReason;
    private final Instant createdAt;
    private Instant decidedAt;

    private Authorization(
            UUID id,
            String cardId,
            Money requestedAmount,
            AuthorizationStatus status,
            AuthorizationDeclineReason declineReason,
            Instant createdAt,
            Instant decidedAt
    ) {
        this.id = Objects.requireNonNull(id);
        this.cardId = requireText(cardId, "cardId");
        this.requestedAmount = Objects.requireNonNull(requestedAmount);
        this.status = Objects.requireNonNull(status);
        this.declineReason = declineReason;
        this.createdAt = Objects.requireNonNull(createdAt);
        this.decidedAt = decidedAt;
        validateDecisionState();
    }

    public static Authorization request(String cardId, Money requestedAmount, Instant createdAt) {
        return new Authorization(
                UUID.randomUUID(),
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
            String cardId,
            Money requestedAmount,
            AuthorizationStatus status,
            AuthorizationDeclineReason declineReason,
            Instant createdAt,
            Instant decidedAt
    ) {
        return new Authorization(
                id,
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
        if (decision.approved()) {
            approve(decisionTime);
        } else {
            decline(decision.declineReason(), decisionTime);
        }
    }

    public void approve(Instant decisionTime) {
        ensurePending("approve");
        status = AuthorizationStatus.APPROVED;
        declineReason = null;
        decidedAt = Objects.requireNonNull(decisionTime);
    }

    public void decline(AuthorizationDeclineReason reason, Instant decisionTime) {
        ensurePending("decline");
        status = AuthorizationStatus.DECLINED;
        declineReason = Objects.requireNonNull(reason);
        decidedAt = Objects.requireNonNull(decisionTime);
    }

    private void ensurePending(String action) {
        if (status != AuthorizationStatus.PENDING) {
            throw new InvalidAuthorizationStateException(status, action);
        }
    }

    private void validateDecisionState() {
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
