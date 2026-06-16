package com.minicard.notification.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 通知 aggregate root，维护一条客户通知及其 delivery lifecycle。
 *
 * <p>它由 integration event 创建，但 Kafka 不属于领域模型。
 * 这样 delivery 规则可以被 Kafka、admin retry endpoint 或 scheduler 复用。</p>
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
     * 根据 authorization decision 创建通知。
     *
     * <p>source event id 被保留为 idempotency key。Kafka 是 at-least-once delivery，
     * 同一个事件可能重复触发创建请求。</p>
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
     * 标记发送成功，避免 provider callback 或重试把同一条逻辑通知发送两次。
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
     * 记录 provider failure。是否耗尽 retry attempts 是业务规则，
     * 因此放在 aggregate 内，而不是散落在 worker/listener。
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
