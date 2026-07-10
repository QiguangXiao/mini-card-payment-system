package com.minicard.notification.domain.delivery;

import java.time.Duration;
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
 * <p>状态转换表（方法 / 推动方）：</p>
 * <pre>
 * (创建)     -> PENDING     pendingFor()               Kafka listener 收到业务事件，与 Notification 同事务写入
 * PENDING    -> PROCESSING  markProcessing()           claimer 短事务写 lease（token + deadline）
 * PROCESSING -> SENT        markSent()                 worker finalize：provider 已回执（终态）
 * PROCESSING -> PENDING     markFailed()               worker 失败 finalize / recoverer lease 超时，backoff 后重试
 * PROCESSING -> PENDING     rescheduleWithoutAttempt()  provider 未调用/worker 未执行，仅延后且不增加 attempts
 * PROCESSING -> DEAD        markFailed()               attempts >= maxAttempts（终态，等人工重放）
 * PROCESSING -> DEAD        markPermanentFailed()      provider 4xx 等永久失败，不消耗完整 retry budget
 * </pre>
 *
 * <p>划分逻辑：没有任何 API 直接推动投递状态——上游（messaging listener）只负责创建 PENDING 行，
 * 之后全部由后台组件（poller/claimer/worker/recoverer）异步推进。这正是"通知"适合完全异步的原因：
 * 用户请求不需要等推送结果，失败重试也不该阻塞业务事务。</p>
 *
 * <p><b>lease 的两个维度分开表达</b>：{@code nextAttemptAt} 是 lease <i>deadline</i>(WHEN 到期，供 recoverer
 * 扫描)；{@code leaseToken} 是 lease <i>identity</i>(WHO 持有，供 worker finalize 校验)。
 * 不能用 nextAttemptAt 兼任 token：它是 {@code Instant.now()}，纳秒精度经 TIMESTAMP(6) 微秒列 round-trip
 * 后会被截断，内存值与回读值不再 equals，导致已成功的投递被误判"lease changed"而最终 DEAD；
 * 同一微秒的两次 claim 也会产生相同时间戳令牌。独立 UUID token 同时避开这两个坑。</p>
 */
// 只读 getter + 私有构造：状态只能经上方转换表列出的 mark*/reschedule* 方法流转，杜绝外部直接改 status。
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

    /**
     * 领取待投递记录并写入 PROCESSING lease，随后 worker 才能在事务外调用 provider。
     */
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

    /**
     * 在 provider 确认成功后把 delivery 标记为 SENT，并记录 provider 回执。
     */
    public void markSent(Instant sentAt, String providerMessageId) {
        // SENT 是终态：provider 已确认。记录 providerMessageId 作为成功证据与对账线索；释放 lease。
        status = NotificationDeliveryStatus.SENT;
        this.sentAt = Objects.requireNonNull(sentAt);
        this.providerMessageId = requireText(providerMessageId, "providerMessageId");
        this.lastError = null;
        this.leaseToken = null;
        updatedAt = sentAt;
    }

    /**
     * 记录一次投递失败，并按 retry policy 决定继续排队还是进入 DEAD。
     */
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

    /**
     * 外部副作用尚未开始时释放 lease 并延后重新领取，但不消耗 durable retry budget。
     *
     * <p>典型来源是本地 RateLimiter 无 permit 或 worker pool 拒绝：两者都没有调用 provider。
     * 如果复用 {@link #markFailed(String, Instant, int)}，持续 backlog 会让健康消息仅因本地容量不足
     * 耗尽 attempts 进入 DEAD。这里仍设置 nextAttemptAt，避免立即重新领取形成 busy loop。</p>
     */
    public void rescheduleWithoutAttempt(String reason, Instant deferredAt, Duration retryDelay) {
        Instant actualDeferredAt = Objects.requireNonNull(deferredAt);
        Duration actualRetryDelay = Objects.requireNonNull(retryDelay);
        if (actualRetryDelay.isNegative() || actualRetryDelay.isZero()) {
            throw new IllegalArgumentException("retryDelay must be positive");
        }
        // attempts 刻意不变：本轮没有发起 provider HTTP，不应占用“真实投递失败”的预算。
        lastError = truncate(requireText(reason, "reason"));
        updatedAt = actualDeferredAt;
        leaseToken = null;
        status = NotificationDeliveryStatus.PENDING;
        nextAttemptAt = actualDeferredAt.plus(actualRetryDelay);
    }

    /**
     * 记录永久性投递失败，并直接进入 DEAD。
     *
     * <p>典型来源是 provider 4xx：请求格式、认证、权限或 endpoint 配错。继续自动 retry 不会让它恢复，
     * 反而会把同一条坏请求打满 8 次并掩盖真正需要人工修复的 contract/config 问题。</p>
     */
    public void markPermanentFailed(String error, Instant failedAt) {
        attempts++;
        lastError = truncate(requireText(error, "error"));
        Instant actualFailedAt = Objects.requireNonNull(failedAt);
        updatedAt = actualFailedAt;
        leaseToken = null;
        status = NotificationDeliveryStatus.DEAD;
        nextAttemptAt = actualFailedAt;
    }

    /**
     * 将超时的 PROCESSING delivery 视作一次失败，交回 retry/DEAD 状态机。
     */
    public void markProcessingTimedOut(Instant recoveredAt, int maxAttempts) {
        // PROCESSING lease 超时通常意味着 worker 宕机或卡住，按一次失败处理，统一走 retry/backoff/DEAD。
        markFailed("notification delivery lease expired", recoveredAt, maxAttempts);
    }

    /**
     * 裁剪 provider 错误信息，避免 last_error 列长度成为新的失败点。
     */
    private String truncate(String value) {
        return value.length() <= MAX_ERROR_LENGTH ? value : value.substring(0, MAX_ERROR_LENGTH);
    }

    /**
     * 校验 delivery 状态和 leaseToken 是否匹配，避免迟到 worker 误判所有权。
     */
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
