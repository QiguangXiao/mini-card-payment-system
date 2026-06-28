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
 * 且 finalize 前重新 FOR UPDATE 锁住并校验 lease token(status==PROCESSING 且 nextAttemptAt 未变)，
 * 防止迟到 worker 覆盖 recoverer/新 worker 的结果。</p>
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
        this.recipientResolver = recipientResolver;
        this.templateRenderer = templateRenderer;
        this.resilientSender = resilientSender;
        this.clock = clock;
        this.transactionOperations = transactionOperations;
    }

    public void handleClaimedDelivery(NotificationDelivery claimed) {
        try {
            NotificationRecipient recipient = recipientResolver.resolve(claimed.recipientKey());
            String address = recipient.addressFor(claimed.channel());
            if (address == null || address.isBlank()) {
                // 收件人在该渠道没有地址：当作一次失败，走退避重试，最终 DEAD 等人工处理。
                // 不静默标 SENT——那会假装"已通知"，用户其实什么都没收到。
                markFailed(claimed, "no recipient address for channel " + claimed.channel(), null);
                return;
            }

            NotificationContent content = templateRenderer.render(
                    claimed.notificationType(), claimed.channel(), claimed.subjectId());
            NotificationDispatch dispatch = new NotificationDispatch(
                    claimed.channel(), address, content, claimed.idempotencyKey());

            // provider 调用在事务外，由 ResilientNotificationSender 套 timeout/retry/circuit breaker。
            ProviderReceipt receipt = resilientSender.send(dispatch);
            markSent(claimed, receipt);
        } catch (RuntimeException exception) {
            markFailed(
                    claimed,
                    exception.getMessage() == null
                            ? exception.getClass().getSimpleName()
                            : exception.getMessage(),
                    exception
            );
        }
    }

    public void markRejectedForRetry(NotificationDelivery claimed, RuntimeException exception) {
        // worker pool 拒绝也按失败处理，把投递从 PROCESSING 放回 retry/DEAD，避免一直卡到 lease 过期。
        markFailed(claimed, "notification delivery worker pool rejected", exception);
    }

    private void markSent(NotificationDelivery claimed, ProviderReceipt receipt) {
        transactionOperations.executeWithoutResult(status -> {
            NotificationDelivery delivery = lockCurrentLease(claimed);
            if (delivery == null) {
                return;
            }
            delivery.markSent(Instant.now(clock), receipt.providerMessageId());
            deliveryRepository.updateDeliveryState(delivery);
            log.info(
                    "notification_delivery_sent deliveryId={} channel={} type={} messageId={}",
                    delivery.id(), delivery.channel(), delivery.notificationType(),
                    receipt.providerMessageId()
            );
        });
    }

    private void markFailed(NotificationDelivery claimed, String error, RuntimeException exception) {
        transactionOperations.executeWithoutResult(status -> {
            NotificationDelivery delivery = lockCurrentLease(claimed);
            if (delivery == null) {
                return;
            }
            delivery.markFailed(error, Instant.now(clock), properties.maxAttempts());
            deliveryRepository.updateDeliveryState(delivery);
            log.warn(
                    "notification_delivery_failed deliveryId={} channel={} attempts={} status={}",
                    delivery.id(), delivery.channel(), delivery.attempts(), delivery.status(),
                    exception
            );
        });
    }

    private NotificationDelivery lockCurrentLease(NotificationDelivery claimed) {
        NotificationDelivery delivery = deliveryRepository.findByIdForUpdate(claimed.id())
                .orElseThrow(() -> new IllegalStateException(
                        "claimed notification delivery disappeared " + claimed.id()));
        // 用 lease_token(UUID, CHAR(36) 精确比较) 判定"还是本 worker 持有"，不用 nextAttemptAt：
        // 后者是 Instant，纳秒精度经 TIMESTAMP(6) round-trip 后被截断，会把已成功投递误判为 lease changed。
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
