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
 * <p>关键词：投递记录, 处理租约, 租约令牌, 指数退避, notification delivery,
 * processing lease, lease token, exponential backoff, idempotency key, 配信レコード(はいしんレコード)。</p>
 *
 * <p>它和 {@link com.minicard.messaging.outbox.OutboxEvent} 是同一套可靠投递状态机：
 * PENDING → PROCESSING(lease) → SENT / 退避回 PENDING / DEAD。区别只在副作用是"调 push/email provider"
 * 而非"发 Kafka"。notificationType/subjectId/recipientKey 是 Notification 的快照，使一条投递自洽，
 * worker 无需回查 notifications 即可渲染并发送。</p>
 *
 * <p><b>lease 的两个维度分开表达</b>：{@code nextAttemptAt} 是 lease <i>deadline</i>(WHEN 到期，供 recoverer
 * 扫描)；{@code leaseToken} 是 lease <i>identity</i>(WHO 持有，供 worker finalize 校验)。
 * 不能用 nextAttemptAt 兼任 token：它是 {@code Instant.now()}，纳秒精度经 TIMESTAMP(6) 微秒列 round-trip
 * 后会被截断，内存值与回读值不再 equals，导致已成功的投递被误判"lease changed"而最终 DEAD；
 * 同一微秒的两次 claim 也会产生相同时间戳令牌。独立 UUID token 同时避开这两个坑。</p>
 */
// 只读 getter + 私有构造：状态只能经 markProcessing/markSent/markFailed 流转，杜绝外部直接改 status。
@Getter
@Accessors(fluent = true)
public final class NotificationDelivery {

    private static final int MAX_ERROR_LENGTH = 500;
    private static final long MAX_RETRY_DELAY_SECONDS = 60;

    /** 单渠道投递 id；也作为 provider idempotency key，保证 retry 不重复发送同一渠道消息。 */
    private final UUID id;
    /** 所属 Notification 意图 id；同一 notification 可以拆出 push/email 等多条 delivery。 */
    private final UUID notificationId;
    /** 投递渠道，例如 APP_PUSH 或 EMAIL；worker 按它选择 channel sender。 */
    private final NotificationChannel channel;
    /** 通知类型快照；即使原 Notification 未来扩展，也不影响这条 delivery 的渲染语义。 */
    private final NotificationType notificationType;
    /** 业务主题 id，例如 authorizationId、cardTransactionId 或 statementId。 */
    private final String subjectId;
    /** 收件人解析 key；当前项目无 User 模型，所以暂用 cardId/creditAccountId 等业务线索。 */
    private final String recipientKey;
    /** 投递状态机：PENDING、PROCESSING、SENT、DEAD。 */
    private NotificationDeliveryStatus status;
    /** 已失败尝试次数；用于 provider 抖动时的 backoff 和 DEAD 判断。 */
    private int attempts;
    /** PENDING 时是下次可投递时间；PROCESSING 时是 lease deadline。 */
    private Instant nextAttemptAt;
    /** PROCESSING lease 的 owner token；provider 调用结束后 finalize 必须重新校验。 */
    private String leaseToken;
    /** 最近一次 provider/渲染/发送失败原因，截断后落库供排查。 */
    private String lastError;
    /** provider 成功回执 id；用于证明已发送，也可作为后续对账线索。 */
    private String providerMessageId;
    /** provider 确认成功的时间；只有 SENT 状态才应出现。 */
    private Instant sentAt;
    /** delivery 创建时间，通常和 Notification 意图在同一事务内写入。 */
    private final Instant createdAt;
    /** 最近一次状态变化时间，包含 claim、失败重试、发送成功。 */
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
            String leaseToken,
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
        this.leaseToken = leaseToken;
        this.lastError = lastError;
        this.providerMessageId = providerMessageId;
        this.sentAt = sentAt;
        this.createdAt = Objects.requireNonNull(createdAt);
        this.updatedAt = Objects.requireNonNull(updatedAt);
        validateLeaseState();
    }

    /**
     * 为某条通知的某个渠道创建待投递记录。
     *
     * <p>nextAttemptAt 设为 now，表示创建即可被 poller 立刻领取；快照通知的 type/subjectId/recipientKey。
     * leaseToken 为 null：尚未被任何 worker 领取。</p>
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
            String leaseToken,
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
                leaseToken,
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
     * 就能逼近"有效恰好一次(effectively-once)"。它必须在多次重试间保持不变，所以直接用 delivery id。
     * 注意它与 leaseToken 不同：idempotencyKey 跨重试不变(对 provider 去重)，leaseToken 每次 claim 都换
     * (标识本轮租约持有者)。</p>
     */
    public String idempotencyKey() {
        return id.toString();
    }

    public void markProcessing(Instant startedAt, long processingTimeoutSeconds, String leaseToken) {
        Instant actualStartedAt = Objects.requireNonNull(startedAt);
        if (processingTimeoutSeconds <= 0) {
            throw new IllegalArgumentException("processingTimeoutSeconds must be positive");
        }
        // PROCESSING 是短租约：nextAttemptAt 复用为 lease deadline(供 recoverer 扫描)，
        // leaseToken 标识本轮持有者(供 worker finalize 校验，避免迟到 worker 覆盖)。
        status = NotificationDeliveryStatus.PROCESSING;
        nextAttemptAt = actualStartedAt.plusSeconds(processingTimeoutSeconds);
        this.leaseToken = requireText(leaseToken, "leaseToken");
        updatedAt = actualStartedAt;
    }

    public void markSent(Instant sentAt, String providerMessageId) {
        // SENT 是终态：provider 已确认。记录 providerMessageId 作为成功证据与对账线索；释放 lease。
        status = NotificationDeliveryStatus.SENT;
        this.sentAt = Objects.requireNonNull(sentAt);
        this.providerMessageId = requireText(providerMessageId, "providerMessageId");
        this.lastError = null;
        this.leaseToken = null;
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
        // 释放 lease：回到 PENDING/DEAD 后本轮 leaseToken 不再有效，迟到 worker 的 finalize 会因 token 不符被丢弃。
        leaseToken = null;
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

    private void validateLeaseState() {
        if (leaseToken != null && leaseToken.isBlank()) {
            throw new IllegalArgumentException("lease token must not be blank");
        }
        // PROCESSING 必须有 token；SENT/PENDING/DEAD 不能残留 token。
        // 如果旧 token 被持久化残留，迟到 worker 可能误以为自己仍拥有本轮 lease。
        boolean hasLeaseToken = leaseToken != null;
        if (status == NotificationDeliveryStatus.PROCESSING && !hasLeaseToken) {
            throw new IllegalArgumentException("processing notification delivery requires lease token");
        }
        if (status != NotificationDeliveryStatus.PROCESSING && hasLeaseToken) {
            throw new IllegalArgumentException("non-processing notification delivery cannot keep lease token");
        }
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
