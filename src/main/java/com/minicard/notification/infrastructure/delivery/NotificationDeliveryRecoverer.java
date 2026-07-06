package com.minicard.notification.infrastructure.delivery;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import com.minicard.notification.application.delivery.NotificationDeliveryProperties;
import com.minicard.notification.domain.delivery.NotificationDelivery;
import com.minicard.notification.domain.delivery.NotificationDeliveryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 恢复长期停留在 PROCESSING 的投递记录。
 *
 * <p>关键词：投递恢复, 处理租约, 重试, delivery recovery,
 * processing lease, retry, 配信復旧(はいしんふっきゅう)。</p>
 *
 * <p>worker 在 PROCESSING 后宕机/卡死时，没有 recoverer 这些投递会永远不可见。recoverer 在 lease
 * 到期后把它们按一次失败处理，统一走退避重试或 DEAD，是可靠投递闭环的最后一环。</p>
 *
 * <p>recoverer 兜底的是"worker 在 claim 之后永远回不来"的时间线：</p>
 * <pre>
 * t0  worker claim:  PENDING -> PROCESSING(token=X, lease deadline=t0+processing-timeout-seconds)
 * t1  pod 宕机/provider 调用卡死：finalize 永远不会执行，row 停在 PROCESSING
 * t2  (>deadline) recoverer 扫描到超时 row -> 按一次失败处理:
 *       attempts+1 未达 maxAttempts -> PENDING(nextAttemptAt=now+backoff)
 *       attempts+1 >= maxAttempts -> DEAD
 * t3  poller 下一轮重新 claim（生成新 token），重新调 provider
 * </pre>
 *
 * <p>注意 t1 有两种可能：provider 根本没收到请求，或已发送成功但回执/finalize 前宕机。
     * recoverer 无法区分，只能统一重发——所以投递是 at-least-once，t3 会带着同一个
     * idempotencyKey（= delivery id）重试。真实 provider 必须支持并接收这个幂等键；
     * 否则系统仍可能重复通知用户，不能把本地重试误讲成端到端 exactly-once。
 * 若 t1 的 worker 只是慢而非死，t3 之后它迟到 finalize 会因 leaseToken 不匹配被拒
 * （见 NotificationDeliveryWorker 的 stale worker 时间线）。</p>
 */
@Component
@ConditionalOnProperty(prefix = "notification.delivery", name = "enabled", havingValue = "true")
@Slf4j
@RequiredArgsConstructor
public class NotificationDeliveryRecoverer {

    private final NotificationDeliveryRepository deliveryRepository;
    private final NotificationDeliveryProperties properties;
    private final Clock clock;

    /**
     * 周期性恢复 lease 超时的 PROCESSING 投递，避免 provider 调用卡死后永久不可见。
     */
    @Scheduled(
            fixedDelayString = "${notification.delivery.recovery-fixed-delay-ms:5000}",
            scheduler = "notificationDeliveryTaskScheduler"
    )
    // recoverer 自己开短事务批量锁住 stuck rows；不复用 worker finalize 事务，扫描与单条处理边界分清。
    @Transactional
    public void recoverStuckDeliveries() {
        Instant now = Instant.now(clock);
        // 阶段 1：扫描 PROCESSING lease 已过期的 delivery，并用 SKIP LOCKED 避免多实例重复恢复。
        List<NotificationDelivery> deliveries = deliveryRepository.findStuckProcessingBatchForUpdate(
                now,
                properties.batchSize()
        );
        for (NotificationDelivery delivery : deliveries) {
            // 阶段 2：把超时 PROCESSING 当作一次投递失败，推进 retry/DEAD。
            // provider 调用可能已经卡死或 worker 已宕机；状态机必须把 row 放回可见队列。
            delivery.markProcessingTimedOut(now, properties.maxAttempts());
            // 阶段 3：持久化恢复结果。回到 PENDING 的 delivery 会在下一轮 poll 中重新领取。
            deliveryRepository.updateDeliveryState(delivery);
            log.warn(
                    "notification_delivery_recovered deliveryId={} channel={} attempts={} status={}",
                    delivery.id(), delivery.channel(), delivery.attempts(), delivery.status()
            );
        }
    }
}
