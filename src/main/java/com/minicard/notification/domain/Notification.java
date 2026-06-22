package com.minicard.notification.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import lombok.Getter;
import lombok.experimental.Accessors;

/**
 * 通知 aggregate root，维护一条客户通知及其 delivery lifecycle。
 *
 * <p>关键词：通知聚合, 投递生命周期, source event id, notification aggregate,
 * delivery lifecycle, idempotency, 通知集約(つうちしゅうやく),
 * 配信(はいしん)。</p>
 *
 * <p>它由 integration event 创建，但 Kafka 不属于领域模型。
 * 这样 delivery 规则可以被 Kafka、admin retry endpoint 或 scheduler 复用。</p>
 */
// Lombok @Getter 只生成 getter，不生成 setter；domain 状态仍只能通过 markSent/recordDeliveryFailure 改变。
// @Accessors(fluent = true) 让调用方使用 notification.status()，保持和 Java record/domain 风格一致。
// 如果用 @Data，会额外生成 setter 和 equals/hashCode，容易绕过 delivery lifecycle。
@Getter
@Accessors(fluent = true)
public class Notification {

    private final UUID id;
    private final UUID sourceEventId;
    private final NotificationSubjectType subjectType;
    private final String subjectId;
    private final String recipientKey;
    private final NotificationType type;
    private final Instant createdAt;
    private NotificationStatus status;
    private int deliveryAttempts;
    private String lastError;
    private Instant sentAt;
    private Instant updatedAt;

    private Notification(
            UUID id,
            UUID sourceEventId,
            NotificationSubjectType subjectType,
            String subjectId,
            String recipientKey,
            NotificationType type,
            NotificationStatus status,
            int deliveryAttempts,
            String lastError,
            Instant sentAt,
            Instant createdAt,
            Instant updatedAt
    ) {
        this.id = Objects.requireNonNull(id);
        this.sourceEventId = Objects.requireNonNull(sourceEventId);
        this.subjectType = Objects.requireNonNull(subjectType);
        this.subjectId = requireText(subjectId, "subjectId");
        this.recipientKey = requireText(recipientKey, "recipientKey");
        this.type = Objects.requireNonNull(type);
        this.status = Objects.requireNonNull(status);
        this.deliveryAttempts = deliveryAttempts;
        this.lastError = lastError;
        this.sentAt = sentAt;
        this.createdAt = Objects.requireNonNull(createdAt);
        this.updatedAt = Objects.requireNonNull(updatedAt);
    }

    /**
     * 根据 integration event 创建通知。
     *
     * <p>这里生成 notification id；source event id 被保留为 idempotency key。
     * Kafka 是 at-least-once delivery，同一个事件可能重复触发创建请求，
     * repository 里的唯一索引会保证最终只落一条通知。</p>
     *
     * <p>提醒：当前项目还没有 Cardholder/User 领域，所以 recipientKey 只是通知路由线索。
     * Authorization/CardTransaction 用 cardId，Statement/Repayment 用 creditAccountId。
     * 以后接真实用户模型时，这里应该改成 customerId 或 notification preference lookup，
     * 而不是继续把账户 id 当用户。</p>
     */
    public static Notification requestFromEvent(
            UUID sourceEventId,
            NotificationSubjectType subjectType,
            String subjectId,
            String recipientKey,
            NotificationType type,
            Instant now
    ) {
        return new Notification(
                UUID.randomUUID(),
                sourceEventId,
                subjectType,
                subjectId,
                recipientKey,
                type,
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

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
