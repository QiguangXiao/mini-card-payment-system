package com.minicard.notification.infrastructure.delivery;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import com.minicard.notification.application.NotificationDeliveryProperties;
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
