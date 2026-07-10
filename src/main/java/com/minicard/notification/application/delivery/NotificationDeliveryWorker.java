package com.minicard.notification.application.delivery;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import com.minicard.notification.domain.delivery.NotificationChannel;
import com.minicard.notification.domain.delivery.NotificationDelivery;
import com.minicard.notification.domain.delivery.NotificationDeliveryRepository;
import com.minicard.notification.domain.delivery.NotificationDeliverySender;
import com.minicard.notification.domain.delivery.NotificationDeliveryPermanentException;
import com.minicard.notification.domain.delivery.NotificationDeliveryStatus;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 投递 worker：领取后的 delivery 在事务外调单渠道 sender，再在独立短事务里 finalize。
 *
 * <p>关键词：投递执行, 事务外副作用, lease 校验, delivery worker,
 * side-effect outside tx, lease revalidation, 配信ワーカー(はいしんワーカー)。</p>
 *
 * <p>结构照搬 OutboxWorker：等待 provider 回执的耗时不占用 DB 事务，只在更新投递状态时短暂开事务，
 * 且 finalize 前重新 FOR UPDATE 锁住并校验 lease token(status==PROCESSING 且 leaseToken 未变)，
 * 防止迟到 worker 覆盖 recoverer/新 worker 的结果。</p>
 *
 * <p>包约定：notification 内部有"请求创建"和"投递执行"两条子线。domain/delivery、
 * infrastructure/delivery 已按此切分，application/delivery（本包）补齐第三层——
 * 三层看到的是同一张"请求 vs 投递"地图；请求侧的 RequestNotificationService 留在 application 根包。</p>
 *
 * <p>流程总览（mini trace，三段式：claim 短事务 / 事务外 send / finalize 短事务）：</p>
 * <pre>
 * 前置: claimer 短事务把 PENDING 改成 PROCESSING + 新 leaseToken（本类拿到的是快照）
 * 1. 按 channel 从注册表选择 NotificationDeliverySender（启动期已 fail fast 校验完整性）
 * 2. [事务外] sender.send(delivery)：sender 内部组装地址/文案，并调用 provider
 *    （Retry + RateLimiter + CircuitBreaker；真实 HTTP timeout 放在 HTTP client/SDK）
 * 3. 成功: finalize 短事务
 *    3.1 SELECT delivery FOR UPDATE + lease 校验（status==PROCESSING 且 leaseToken 未变）；
 *        lease 已变则 skip（迟到回执不能覆盖新 owner 的结果）
 *    3.2 markSent(providerMessageId)，释放 lease
 * 4. 未执行/失败: 同样走 finalize 短事务 + lease 校验
 *    4.1 throttled/worker pool 拒绝: rescheduleWithoutAttempt，只延后且不增加 attempts
 *    4.2 transient failure(timeout/5xx): markFailed，attempts+backoff 回 PENDING 或进 DEAD
 *    4.3 permanent failure(provider 4xx): markPermanentFailed，直接 DEAD，避免无意义重试
 * </pre>
 *
 * <p>stale worker 时间线（为什么 finalize 必须校验 leaseToken，而不能只看 status）：</p>
 * <pre>
 * t0  worker A claim:  PENDING -> PROCESSING(token=A, lease deadline=t0+processing-timeout-seconds)
 * t1  A 在事务外等 provider 回执，provider 响应很慢（HTTP client timeout / retry 耗时叠加）
 * t2  deadline 已过，recoverer 扫描: markProcessingTimedOut -> PENDING（token 清空）
 * t3  worker B claim:  PENDING -> PROCESSING(token=B, 新 deadline)，再次调 provider
 * t4  B 拿到回执 -> finalize: DB token=B 与自己相符 -> SENT(providerMessageId)
 * t5  A 的回执迟到返回 -> finalize: DB 已非 PROCESSING（或 token=B != A）-> skip
 * </pre>
 *
 * <p>t5 若不 skip，A 会覆盖 B 已写入的 providerMessageId（A 失败时甚至把 SENT 打回
 * PENDING，触发第三次发送）。且 t1 和 t3 都可能真的发出了推送——所以投递是 at-least-once，
 * 因此必须把 idempotencyKey（= delivery id，跨 retry 不变）透传给支持幂等的 provider；
 * 若真实 provider 不支持幂等，仍要接受 at-least-once 投递可能让用户收到重复通知。</p>
 */
@Service
@Slf4j
public class NotificationDeliveryWorker {

    private final NotificationDeliveryRepository deliveryRepository;
    private final NotificationDeliveryProperties properties;
    private final Map<NotificationChannel, NotificationDeliverySender> senders;
    private final Clock clock;
    private final TransactionOperations transactionOperations;
    private final MeterRegistry meterRegistry;

    public NotificationDeliveryWorker(
            NotificationDeliveryRepository deliveryRepository,
            NotificationDeliveryProperties properties,
            List<NotificationDeliverySender> deliverySenders,
            Clock clock,
            TransactionOperations transactionOperations,
            MeterRegistry meterRegistry
    ) {
        this.deliveryRepository = deliveryRepository;
        this.properties = properties;
        // 启动时把 List 收成 EnumMap：热路径 O(1) 查找，并把"缺渠道实现"提前暴露为启动失败。
        // 如果等到运行期才发现没有 EMAIL sender，该渠道所有 delivery 会白白 retry 到 DEAD。
        this.senders = sendersByChannel(deliverySenders);
        this.clock = clock;
        this.transactionOperations = transactionOperations;
        this.meterRegistry = meterRegistry;
    }

    /**
     * 执行一条已领取的投递记录，完成单渠道 provider 调用和状态 finalize。
     */
    public void handleClaimedDelivery(NotificationDelivery claimed) {
        try {
            // 阶段 1：按 delivery.channel() 找到唯一 sender。注册表在构造器已校验完整性，
            // 所以这里的 null 只可能来自未来 enum/配置不一致，仍按配置错误 fail fast。
            NotificationDeliverySender sender = senders.get(claimed.channel());
            if (sender == null) {
                throw new IllegalStateException("no sender registered for channel " + claimed.channel());
            }

            // 阶段 2：事务外调用 provider。sender 内部负责该渠道的地址/文案/Resilience4j；
            // worker 不关心 email/push 细节，只拿 providerMessageId 回来推进状态机。
            // 这样状态机边界更清楚：DB lease/finalize 在 worker，外部副作用在 sender。
            String providerMessageId = sender.send(claimed);
            // 阶段 3：provider 返回成功回执后，才在短事务里校验 lease 并标 SENT。
            markSent(claimed, providerMessageId);
        } catch (NotificationDeliveryThrottledException exception) {
            // RateLimiter 拒绝发生在 provider HTTP 之前：只释放 lease 并延后到下一 poll 周期，
            // 不增加 attempts。否则健康 provider 也可能仅因本地 backlog 被耗到 DEAD。
            meterRegistry.counter("notification.delivery.throttled").increment();
            rescheduleWithoutAttempt(claimed, "notification provider throttled", exception);
        } catch (NotificationDeliveryPermanentException exception) {
            // provider 4xx 等永久失败：请求/凭证/endpoint 不修，retry 多少次都不会成功。
            // 直接 DEAD 让问题尽快暴露给人工/admin，而不是白白消耗 8 次 durable retry。
            markPermanentFailed(claimed, exception.getMessage(), exception);
        } catch (RuntimeException exception) {
            // 任一环失败都走统一失败 finalize：记录 lastError、attempts 和下一次 retry 时间。
            // 不在这里吞异常后直接返回，否则 PROCESSING lease 会一直等 recoverer 才能释放。
            markFailed(
                    claimed,
                    exception.getMessage() == null
                            ? exception.getClass().getSimpleName()
                            : exception.getMessage(),
                    exception
            );
        }
    }

    /**
     * worker pool 拒绝执行时，把已领取投递延后到下一轮，但不消耗投递 attempts。
     *
     * <p>事务归属：本方法本身不加 {@code @Transactional}；它委托
     * {@link #rescheduleWithoutAttempt(NotificationDelivery, String, RuntimeException)}
     * 开启 finalize 短事务。</p>
     */
    public void markRejectedForRetry(NotificationDelivery claimed, RuntimeException exception) {
        // worker pool 拒绝发生在 provider 调用前，和 throttling 一样不应增加 attempts；
        // 但 DB row 已经是 PROCESSING，所以仍要短事务释放 lease 并推迟 nextAttemptAt。
        rescheduleWithoutAttempt(
                claimed,
                "notification delivery worker pool rejected",
                exception
        );
    }

    /**
     * 在独立短事务中释放当前 lease，并延后至少一个 poll 周期，不增加 attempts。
     */
    private void rescheduleWithoutAttempt(
            NotificationDelivery claimed,
            String reason,
            RuntimeException exception
    ) {
        transactionOperations.executeWithoutResult(status -> {
            NotificationDelivery delivery = lockCurrentLease(claimed);
            if (delivery == null) {
                return;
            }
            // 复用 poller 的 fixed delay，避免再增加一个 throttle-backoff 配置旋钮。
            // 即使下轮仍拿不到 permit，也会再次延后而不是 busy loop 或错误进入 DEAD。
            delivery.rescheduleWithoutAttempt(
                    reason,
                    Instant.now(clock),
                    Duration.ofMillis(properties.fixedDelayMs())
            );
            deliveryRepository.updateDeliveryState(delivery);
            log.debug(
                    "notification_delivery_rescheduled deliveryId={} channel={} reason={} nextAttemptAt={}",
                    delivery.id(), delivery.channel(), reason, delivery.nextAttemptAt(), exception
            );
        });
    }

    /**
     * 在独立短事务中重新校验 lease，并把 provider 已确认的投递标记为 SENT。
     *
     * <p>事务归属：本方法通过 {@code TransactionOperations.executeWithoutResult(...)}
     * 自己开启短事务；provider 调用和 retry/circuit breaker 都已经在事务外完成。</p>
     */
    private void markSent(NotificationDelivery claimed, String providerMessageId) {
        // 成功 finalize 单独开短事务：只做 lease revalidation 和状态更新，不包住 provider 网络调用。
        transactionOperations.executeWithoutResult(status -> {
            NotificationDelivery delivery = lockCurrentLease(claimed);
            if (delivery == null) {
                return;
            }
            // SENT 记录 providerMessageId 作为已送达证据；同时释放 leaseToken，避免后续 worker 误判仍可处理。
            delivery.markSent(Instant.now(clock), providerMessageId);
            deliveryRepository.updateDeliveryState(delivery);
            log.info(
                    "notification_delivery_sent deliveryId={} channel={} type={} messageId={}",
                    delivery.id(), delivery.channel(), delivery.notificationType(),
                    providerMessageId
            );
        });
    }

    /**
     * 在独立短事务中记录投递失败，并推进 attempts、lastError 和下一次 retry 时间。
     *
     * <p>事务归属：本方法通过 {@code TransactionOperations.executeWithoutResult(...)}
     * 自己开启短事务；它不和 provider 网络调用共用事务。</p>
     */
    private void markFailed(NotificationDelivery claimed, String error, RuntimeException exception) {
        // 失败 finalize 也要独立落库；否则 provider exception 只留在线程日志里，retry/backoff 状态不会推进。
        transactionOperations.executeWithoutResult(status -> {
            NotificationDelivery delivery = lockCurrentLease(claimed);
            if (delivery == null) {
                return;
            }
            // markFailed() 会释放本轮 lease，并按 attempts 决定回 PENDING 还是进入 DEAD。
            // 这保证临时 provider 故障可以 retry，而永久坏数据不会无限发送。
            delivery.markFailed(error, Instant.now(clock), properties.maxAttempts());
            deliveryRepository.updateDeliveryState(delivery);
            log.warn(
                    "notification_delivery_failed deliveryId={} channel={} attempts={} status={}",
                    delivery.id(), delivery.channel(), delivery.attempts(), delivery.status(),
                    exception
            );
        });
    }

    /**
     * 在独立短事务中记录永久性失败，并直接进入 DEAD。
     */
    private void markPermanentFailed(NotificationDelivery claimed, String error, RuntimeException exception) {
        transactionOperations.executeWithoutResult(status -> {
            NotificationDelivery delivery = lockCurrentLease(claimed);
            if (delivery == null) {
                return;
            }
            delivery.markPermanentFailed(error, Instant.now(clock));
            deliveryRepository.updateDeliveryState(delivery);
            log.warn(
                    "notification_delivery_permanent_failed deliveryId={} channel={} attempts={} status={}",
                    delivery.id(), delivery.channel(), delivery.attempts(), delivery.status(),
                    exception
            );
        });
    }

    /**
     * 重新锁定当前 delivery row 并确认本 worker 仍持有 PROCESSING lease。
     *
     * <p>事务归属：只能在 {@link #markSent(NotificationDelivery, String)}
     *、{@link #rescheduleWithoutAttempt(NotificationDelivery, String, RuntimeException)}
     * 或 {@link #markFailed(NotificationDelivery, String, RuntimeException)}
     * 创建的 finalize 短事务内部调用。</p>
     */
    private NotificationDelivery lockCurrentLease(NotificationDelivery claimed) {
        // provider 调用在事务外，可能慢到超过 lease timeout。finalize 前重新 FOR UPDATE 读当前 row，
        // 确认它还属于本 worker，避免迟到回执把 recoverer/新 worker 的结果覆盖掉。
        NotificationDelivery delivery = deliveryRepository.findByIdForUpdate(claimed.id())
                .orElseThrow(() -> new IllegalStateException(
                        "claimed notification delivery disappeared " + claimed.id()));
        // 三个条件逐一保护：
        // 1) status 仍为 PROCESSING，表示还处在可 finalize 的租约状态。
        // 2) claimed snapshot 必须有 leaseToken；没有 token 的对象不能证明自己来自合法 claim。
        // 3) DB 当前 token 必须等于 claimed token；nextAttemptAt 是 deadline/retry 时间，不能当 owner identity。
        if (delivery.status() != NotificationDeliveryStatus.PROCESSING
                || claimed.leaseToken() == null
                || !claimed.leaseToken().equals(delivery.leaseToken())) {
            // lease 已变：recoverer 或新 worker 接管过。迟到 worker 不能覆盖它们的结果。
            log.warn(
                    "notification_delivery_lease_changed deliveryId={} claimedToken={} currentStatus={} currentToken={}",
                    claimed.id(), claimed.leaseToken(), delivery.status(), delivery.leaseToken()
            );
            return null;
        }
        return delivery;
    }

    private Map<NotificationChannel, NotificationDeliverySender> sendersByChannel(
            List<NotificationDeliverySender> deliverySenders
    ) {
        Map<NotificationChannel, NotificationDeliverySender> result = new EnumMap<>(NotificationChannel.class);
        for (NotificationDeliverySender sender : deliverySenders) {
            NotificationDeliverySender previous = result.put(sender.channel(), sender);
            if (previous != null) {
                // 同一 channel 两个 sender 是 wiring 歧义：启动即失败，避免运行期随机选中一个。
                throw new IllegalStateException("duplicate sender for channel " + sender.channel());
            }
        }
        EnumSet<NotificationChannel> missingChannels = EnumSet.allOf(NotificationChannel.class);
        missingChannels.removeAll(result.keySet());
        if (!missingChannels.isEmpty()) {
            // 缺 sender 是启动期配置错误，不是某条 delivery 的 transient provider failure。
            // fail fast 比让整个渠道的 delivery 全部 retry 到 DEAD 更便宜、更容易排查。
            throw new IllegalStateException("missing sender for channel(s) " + missingChannels);
        }
        return result;
    }
}
