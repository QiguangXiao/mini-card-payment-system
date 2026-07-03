package com.minicard.notification.application;

import java.time.Clock;
import java.time.Instant;

import com.minicard.notification.domain.delivery.NotificationContent;
import com.minicard.notification.domain.delivery.NotificationDelivery;
import com.minicard.notification.domain.delivery.NotificationDeliveryRepository;
import com.minicard.notification.domain.delivery.NotificationDeliveryStatus;
import com.minicard.notification.domain.delivery.NotificationDispatch;
import com.minicard.notification.domain.delivery.NotificationRecipient;
import com.minicard.notification.domain.delivery.NotificationRecipientResolver;
import com.minicard.notification.domain.delivery.NotificationSender;
import com.minicard.notification.domain.delivery.NotificationTemplateRenderer;
import com.minicard.notification.domain.delivery.ProviderReceipt;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 投递 worker：解析收件人 + 渲染模板 + 调 provider（事务外），再在独立短事务里 finalize。
 *
 * <p>关键词：投递执行, 事务外副作用, lease 校验, delivery worker,
 * side-effect outside tx, lease revalidation, 配信ワーカー(はいしんワーカー)。</p>
 *
 * <p>结构照搬 OutboxWorker：等待 provider 回执的耗时不占用 DB 事务，只在更新投递状态时短暂开事务，
 * 且 finalize 前重新 FOR UPDATE 锁住并校验 lease token(status==PROCESSING 且 leaseToken 未变)，
 * 防止迟到 worker 覆盖 recoverer/新 worker 的结果。</p>
 *
 * <p>流程总览（mini trace，三段式：claim 短事务 / 事务外 send / finalize 短事务）：</p>
 * <pre>
 * claimer 短事务: PENDING -&gt; PROCESSING + 新 leaseToken（本类拿到的是快照）
 *  -&gt; resolve recipient by recipientKey（delivery row 只存稳定业务 key，不存地址）
 *  -&gt; 该渠道无地址: markFailed（业务失败走 retry/DEAD，不假装 SENT）
 *  -&gt; render template（type/channel/subjectId，不回查 Notification aggregate）
 *  -&gt; [事务外] provider send（timeout/retry/circuit breaker；idempotencyKey = delivery id）
 *  -&gt; 成功: finalize 短事务
 *     -&gt; SELECT delivery FOR UPDATE + lease 校验（status==PROCESSING 且 leaseToken 未变）
 *        -&gt; lease 已变: skip（迟到回执不能覆盖新 owner 的结果）
 *     -&gt; markSent(providerMessageId)，释放 lease
 *  -&gt; 失败/worker pool 拒绝: finalize 短事务
 *     -&gt; 同样 lease 校验 -&gt; markFailed: attempts+backoff -&gt; PENDING 或 DEAD
 * </pre>
 *
 * <p>stale worker 时间线（为什么 finalize 必须校验 leaseToken，而不能只看 status）：</p>
 * <pre>
 * t0  worker A claim:  PENDING -&gt; PROCESSING(token=A, lease deadline=t0+30s)
 * t1  A 在事务外等 provider 回执，provider 响应很慢（timeout/retry 耗时叠加）
 * t2  deadline 已过，recoverer 扫描: markProcessingTimedOut -&gt; PENDING（token 清空）
 * t3  worker B claim:  PENDING -&gt; PROCESSING(token=B, 新 deadline)，再次调 provider
 * t4  B 拿到回执 -&gt; finalize: DB token=B 与自己相符 -&gt; SENT(providerMessageId)
 * t5  A 的回执迟到返回 -&gt; finalize: DB 已非 PROCESSING（或 token=B != A）-&gt; skip
 * </pre>
 *
 * <p>t5 若不 skip，A 会覆盖 B 已写入的 providerMessageId（A 失败时甚至把 SENT 打回
 * PENDING，触发第三次发送）。且 t1 和 t3 都可能真的发出了推送——所以投递是 at-least-once，
 * 靠 idempotencyKey（= delivery id，跨 retry 不变）让 provider 侧去重，用户才不会收到两条。</p>
 */
@Service
@Slf4j
public class NotificationDeliveryWorker {

    private final NotificationDeliveryRepository deliveryRepository;
    private final NotificationDeliveryProperties properties;
    private final NotificationRecipientResolver recipientResolver;
    private final NotificationTemplateRenderer templateRenderer;
    private final NotificationSender resilientSender;
    private final Clock clock;
    private final TransactionOperations transactionOperations;

    public NotificationDeliveryWorker(
            NotificationDeliveryRepository deliveryRepository,
            NotificationDeliveryProperties properties,
            NotificationRecipientResolver recipientResolver,
            NotificationTemplateRenderer templateRenderer,
            NotificationSender resilientSender,
            Clock clock,
            TransactionOperations transactionOperations
    ) {
        this.deliveryRepository = deliveryRepository;
        this.properties = properties;
        // resolver/renderer/sender 三个端口分开：收件人解析、内容渲染、外部发送是不同失败点，
        // 分开后日志和 retry 语义更清楚，测试也可以单独替换某一环。
        this.recipientResolver = recipientResolver;
        this.templateRenderer = templateRenderer;
        this.resilientSender = resilientSender;
        this.clock = clock;
        this.transactionOperations = transactionOperations;
    }

    /**
     * 执行一条已领取的投递记录，完成收件人解析、模板渲染、provider 调用和状态 finalize。
     */
    public void handleClaimedDelivery(NotificationDelivery claimed) {
        try {
            // 第一步：根据 recipientKey 解析收件人。delivery row 持有的是稳定业务 key，
            // 不是具体 email/device token；这样用户联系方式变化时仍可由 resolver 决定最新地址。
            NotificationRecipient recipient = recipientResolver.resolve(claimed.recipientKey());
            // 第二步：按渠道取地址。APP_PUSH 和 EMAIL 的缺失地址是业务失败，不是系统 crash。
            String address = recipient.addressFor(claimed.channel());
            if (address == null || address.isBlank()) {
                // 收件人在该渠道没有地址：当作一次失败，走退避重试，最终 DEAD 等人工处理。
                // 不静默标 SENT——那会假装"已通知"，用户其实什么都没收到。
                markFailed(claimed, "no recipient address for channel " + claimed.channel(), null);
                return;
            }

            // 第三步：用 notificationType/channel/subjectId 渲染本次投递内容。
            // worker 不回查 Notification aggregate；delivery row 已经保存了发送需要的不可变快照。
            NotificationContent content = templateRenderer.render(
                    claimed.notificationType(), claimed.channel(), claimed.subjectId());
            // 第四步：组装发给 sender 的自洽请求。idempotencyKey 来自 delivery id，
            // 跨 retry 保持不变，方便 provider 按同一 key 去重。
            NotificationDispatch dispatch = new NotificationDispatch(
                    claimed.channel(), address, content, claimed.idempotencyKey());

            // 第五步：真正调用外部 provider。这里故意在 DB transaction 外执行，
            // 避免等待网络 timeout/retry 时占住 notification_deliveries row lock。
            // provider 调用在事务外，由 ResilientNotificationSender 套 timeout/retry/circuit breaker。
            ProviderReceipt receipt = resilientSender.send(dispatch);
            // 第六步：provider 返回成功回执后，才在短事务里校验 lease 并标 SENT。
            markSent(claimed, receipt);
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
     * worker pool 拒绝执行时，把已领取投递放回 retry/DEAD 状态机。
     *
     * <p>事务归属：本方法本身不加 {@code @Transactional}；它委托
     * {@link #markFailed(NotificationDelivery, String, RuntimeException)} 开启 finalize 短事务。</p>
     */
    public void markRejectedForRetry(NotificationDelivery claimed, RuntimeException exception) {
        // worker pool 拒绝也按失败处理，把投递从 PROCESSING 放回 retry/DEAD，避免一直卡到 lease 过期。
        // 这类失败发生在 provider 调用前，但 DB row 已经是 PROCESSING，所以仍然需要短事务 finalize。
        markFailed(claimed, "notification delivery worker pool rejected", exception);
    }

    /**
     * 在独立短事务中重新校验 lease，并把 provider 已确认的投递标记为 SENT。
     *
     * <p>事务归属：本方法通过 {@code TransactionOperations.executeWithoutResult(...)}
     * 自己开启短事务；provider 调用和 retry/circuit breaker 都已经在事务外完成。</p>
     */
    private void markSent(NotificationDelivery claimed, ProviderReceipt receipt) {
        // 成功 finalize 单独开短事务：只做 lease revalidation 和状态更新，不包住 provider 网络调用。
        transactionOperations.executeWithoutResult(status -> {
            NotificationDelivery delivery = lockCurrentLease(claimed);
            if (delivery == null) {
                return;
            }
            // SENT 记录 providerMessageId 作为已送达证据；同时释放 leaseToken，避免后续 worker 误判仍可处理。
            delivery.markSent(Instant.now(clock), receipt.providerMessageId());
            deliveryRepository.updateDeliveryState(delivery);
            log.info(
                    "notification_delivery_sent deliveryId={} channel={} type={} messageId={}",
                    delivery.id(), delivery.channel(), delivery.notificationType(),
                    receipt.providerMessageId()
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
     * 重新锁定当前 delivery row 并确认本 worker 仍持有 PROCESSING lease。
     *
     * <p>事务归属：只能在 {@link #markSent(NotificationDelivery, ProviderReceipt)}
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
}
