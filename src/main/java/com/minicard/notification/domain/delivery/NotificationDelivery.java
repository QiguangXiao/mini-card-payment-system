package com.minicard.notification.domain.delivery;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.minicard.notification.domain.Notification;
import com.minicard.notification.domain.NotificationType;
import lombok.Getter;
import lombok.experimental.Accessors;

/**
 * 单条 per-channel 投递记录，拥有自己的投递生命周期(lifecycle)。
 *
 * <p>关键词：投递记录, 处理租约, 指数退避, notification delivery,
 * processing lease, exponential backoff, idempotency key, 配信レコード(はいしんレコード)。</p>
 *
 * <p>它和 {@link com.minicard.messaging.outbox.OutboxEvent} 是同一套可靠投递状态机：
 * PENDING → PROCESSING(lease) → SENT / 退避回 PENDING / DEAD。区别只在副作用是"调 push/email provider"
 * 而非"发 Kafka"。notificationType/subjectId/recipientKey 是 Notification 的快照，使一条投递自洽，
 * worker 无需回查 notifications 即可渲染并发送。</p>
 */
// 只读 getter + 私有构造：状态只能经 markProcessing/markSent/markFailed 流转，杜绝外部直接改 status。
@Getter
@Accessors(fluent = true)
public final class NotificationDelivery {

    private static final int MAX_ERROR_LENGTH = 500;
    private static final long MAX_RETRY_DELAY_SECONDS = 60;

    private final UUID id;
    private final UUID notificationId;
    private final NotificationChannel channel;
    private final NotificationType notificationType;
    private final String subjectId;
    private final String recipientKey;
    private NotificationDeliveryStatus status;
    private int attempts;
    private Instant nextAttemptAt;
    private String lastError;
    private String providerMessageId;
    private Instant sentAt;
    private final Instant createdAt;
    private Instant updatedAt;

    private NotificationDelivery(
            UUID id,
            UUID notificationId,
            NotificationChannel channel,
            NotificationType notificationType,
            String subjectId,
            String recipientKey,
            NotificationDeliveryStatus status,
            int attempts,
            Instant nextAttemptAt,
            String lastError,
            String providerMessageId,
            Instant sentAt,
            Instant createdAt,
            Instant updatedAt
    ) {
        this.id = Objects.requireNonNull(id);
        this.notificationId = Objects.requireNonNull(notificationId);
        this.channel = Objects.requireNonNull(channel);
        this.notificationType = Objects.requireNonNull(notificationType);
        this.subjectId = requireText(subjectId, "subjectId");
        this.recipientKey = requireText(recipientKey, "recipientKey");
        this.status = Objects.requireNonNull(status);
        if (attempts < 0) {
            throw new IllegalArgumentException("attempts must not be negative");
        }
        this.attempts = attempts;
        this.nextAttemptAt = Objects.requireNonNull(nextAttemptAt);
        this.lastError = lastError;
        this.providerMessageId = providerMessageId;
        this.sentAt = sentAt;
        this.createdAt = Objects.requireNonNull(createdAt);
        this.updatedAt = Objects.requireNonNull(updatedAt);
    }

    /**
     * 为某条通知的某个渠道创建待投递记录。
     *
     * <p>nextAttemptAt 设为 now，表示创建即可被 poller 立刻领取；快照通知的 type/subjectId/recipientKey。</p>
     */
    public static NotificationDelivery pendingFor(
            Notification notification,
            NotificationChannel channel,
            Instant now
    ) {
        return new NotificationDelivery(
                UUID.randomUUID(),
                notification.id(),
                channel,
                notification.type(),
                notification.subjectId(),
                notification.recipientKey(),
                NotificationDeliveryStatus.PENDING,
                0,
                now,
                null,
                null,
                null,
                now,
                now
        );
    }

    public static NotificationDelivery restore(
            UUID id,
            UUID notificationId,
            NotificationChannel channel,
            NotificationType notificationType,
            String subjectId,
            String recipientKey,
            NotificationDeliveryStatus status,
            int attempts,
            Instant nextAttemptAt,
            String lastError,
            String providerMessageId,
            Instant sentAt,
            Instant createdAt,
            Instant updatedAt
    ) {
        return new NotificationDelivery(
                id,
                notificationId,
                channel,
                notificationType,
                subjectId,
                recipientKey,
                status,
                attempts,
                nextAttemptAt,
                lastError,
                providerMessageId,
                sentAt,
                createdAt,
                updatedAt
        );
    }

    /**
     * provider 幂等键：稳定且每个 (notification, channel) 唯一。
     *
     * <p>worker 把它透传给 push/email provider；外部接口若按此键去重，"至少一次投递 + 下游去重"
     * 就能逼近"有效恰好一次(effectively-once)"。它必须在多次重试间保持不变，所以直接用 delivery id。</p>
     */
    public String idempotencyKey() {
        return id.toString();
    }

    public void markProcessing(Instant startedAt, long processingTimeoutSeconds) {
        Instant actualStartedAt = Objects.requireNonNull(startedAt);
        if (processingTimeoutSeconds <= 0) {
            throw new IllegalArgumentException("processingTimeoutSeconds must be positive");
        }
        // PROCESSING 是短租约：worker 宕机后 nextAttemptAt(=lease deadline)到期，recoverer 可重新放回队列。
        status = NotificationDeliveryStatus.PROCESSING;
        nextAttemptAt = actualStartedAt.plusSeconds(processingTimeoutSeconds);
        updatedAt = actualStartedAt;
    }

    public void markSent(Instant sentAt, String providerMessageId) {
        // SENT 是终态：provider 已确认。记录 providerMessageId 作为成功证据与对账线索。
        status = NotificationDeliveryStatus.SENT;
        this.sentAt = Objects.requireNonNull(sentAt);
        this.providerMessageId = requireText(providerMessageId, "providerMessageId");
        this.lastError = null;
        updatedAt = sentAt;
    }

    public void markFailed(String error, Instant failedAt, int maxAttempts) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
        attempts++;
        // 先在 domain 内截断错误信息，避免超过 DB varchar(500) 反而把真正的失败原因丢掉。
        lastError = truncate(requireText(error, "error"));
        updatedAt = Objects.requireNonNull(failedAt);
        if (attempts >= maxAttempts) {
            // DEAD 终态：停止自动重试，避免 poison delivery 无限打 provider 并掩盖真实故障，等待人工/admin 重放。
            status = NotificationDeliveryStatus.DEAD;
            nextAttemptAt = failedAt;
            return;
        }
        // 指数退避缓解 provider 抖动；1L<<n 即 2^n，Math.min 限制指数避免 attempts 很大时溢出/等待过久。
        long delaySeconds = Math.min(1L << Math.min(attempts - 1, 6), MAX_RETRY_DELAY_SECONDS);
        status = NotificationDeliveryStatus.PENDING;
        nextAttemptAt = failedAt.plusSeconds(delaySeconds);
    }

    public void markProcessingTimedOut(Instant recoveredAt, int maxAttempts) {
        // PROCESSING lease 超时通常意味着 worker 宕机或卡住，按一次失败处理，统一走 retry/backoff/DEAD。
        markFailed("notification delivery lease expired", recoveredAt, maxAttempts);
    }

    private String truncate(String value) {
        return value.length() <= MAX_ERROR_LENGTH ? value : value.substring(0, MAX_ERROR_LENGTH);
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
