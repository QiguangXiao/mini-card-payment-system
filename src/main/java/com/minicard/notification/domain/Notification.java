package com.minicard.notification.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Aggregate root that owns one customer notification and its delivery lifecycle.
 *
 * <p>The aggregate is created from an integration event, but Kafka is not part
 * of its model. This keeps delivery rules usable from Kafka, an admin retry
 * endpoint, or a scheduled worker without moving business rules into adapters.</p>
 */
public class Notification {

    private final UUID id;
    private final UUID sourceEventId;
    private final UUID authorizationId;
    private final String cardId;
    private final String template;
    private final Instant createdAt;
    private NotificationStatus status;
    private int deliveryAttempts;
    private String lastError;
    private Instant sentAt;
    private Instant updatedAt;

    private Notification(
            UUID id,
            UUID sourceEventId,
            UUID authorizationId,
            String cardId,
            String template,
            NotificationStatus status,
            int deliveryAttempts,
            String lastError,
            Instant sentAt,
            Instant createdAt,
            Instant updatedAt
    ) {
        this.id = Objects.requireNonNull(id);
        this.sourceEventId = Objects.requireNonNull(sourceEventId);
        this.authorizationId = Objects.requireNonNull(authorizationId);
        this.cardId = requireText(cardId, "cardId");
        this.template = requireText(template, "template");
        this.status = Objects.requireNonNull(status);
        this.deliveryAttempts = deliveryAttempts;
        this.lastError = lastError;
        this.sentAt = sentAt;
        this.createdAt = Objects.requireNonNull(createdAt);
        this.updatedAt = Objects.requireNonNull(updatedAt);
    }

    /**
     * Creates a notification requested by an authorization decision.
     *
     * <p>The source event id is retained as the idempotency key. Kafka provides
     * at-least-once delivery, so the same event may request creation repeatedly.</p>
     */
    public static Notification requestAuthorizationDecision(
            UUID sourceEventId,
            UUID authorizationId,
            String cardId,
            boolean approved,
            Instant now
    ) {
        String template = approved
                ? "AUTHORIZATION_APPROVED"
                : "AUTHORIZATION_DECLINED";
        return new Notification(
                UUID.randomUUID(),
                sourceEventId,
                authorizationId,
                cardId,
                template,
                NotificationStatus.PENDING,
                0,
                null,
                null,
                now,
                now
        );
    }

    /**
     * Marks successful delivery and prevents a provider callback from sending
     * the same logical notification twice.
     */
    public void markSent(Instant now) {
        if (status == NotificationStatus.SENT) {
            return;
        }
        if (status == NotificationStatus.FAILED) {
            throw new IllegalStateException("failed notification cannot be marked sent");
        }
        status = NotificationStatus.SENT;
        sentAt = Objects.requireNonNull(now);
        updatedAt = now;
        lastError = null;
    }

    /**
     * Records a provider failure. The aggregate decides when retry attempts are
     * exhausted instead of leaving this business rule in a worker or listener.
     */
    public void recordDeliveryFailure(String error, int maxAttempts, Instant now) {
        if (status != NotificationStatus.PENDING) {
            throw new IllegalStateException("only pending notification can record failure");
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
        deliveryAttempts++;
        lastError = requireText(error, "error");
        updatedAt = Objects.requireNonNull(now);
        if (deliveryAttempts >= maxAttempts) {
            status = NotificationStatus.FAILED;
        }
    }

    public UUID id() {
        return id;
    }

    public UUID sourceEventId() {
        return sourceEventId;
    }

    public UUID authorizationId() {
        return authorizationId;
    }

    public String cardId() {
        return cardId;
    }

    public String template() {
        return template;
    }

    public NotificationStatus status() {
        return status;
    }

    public int deliveryAttempts() {
        return deliveryAttempts;
    }

    public String lastError() {
        return lastError;
    }

    public Instant sentAt() {
        return sentAt;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
