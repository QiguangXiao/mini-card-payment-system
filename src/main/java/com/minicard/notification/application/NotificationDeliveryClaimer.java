package com.minicard.notification.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.minicard.notification.domain.delivery.NotificationDelivery;
import com.minicard.notification.domain.delivery.NotificationDeliveryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 在短事务里批量领取待投递记录并打 PROCESSING lease。
 *
 * <p>关键词：投递领取, 行锁, PROCESSING lease, delivery claim,
 * FOR UPDATE SKIP LOCKED, short transaction, 配信取得(はいしんしゅとく)。</p>
 *
 * <p>与 OutboxClaimer 对称：claim 必须是短事务——只锁定、改 lease、提交。外部 provider 调用绝不能
 * 放进这个事务，否则 provider latency 会被放大成 MySQL row lock 持有时间，拖垮其他写入。</p>
 */
@Service
@RequiredArgsConstructor
public class NotificationDeliveryClaimer {

    private final NotificationDeliveryRepository deliveryRepository;
    private final NotificationDeliveryProperties properties;
    private final Clock clock;

    /**
     * 领取可投递记录并写入 PROCESSING lease，随后交给 worker 在事务外调用 provider。
     */
    @Transactional
    public List<NotificationDelivery> claimDispatchableDeliveries() {
        Instant now = Instant.now(clock);
        // 阶段 1：用 FOR UPDATE SKIP LOCKED 扫描 due PENDING delivery。
        // 本模型没有 FAILED 状态；失败后 markFailed() 会回到 PENDING 并推迟 nextAttemptAt，或进入 DEAD。
        // 多实例并发时，被其他事务锁住的 row 会被跳过，不会阻塞整个投递扫描。
        List<NotificationDelivery> deliveries = deliveryRepository.findDispatchableBatchForUpdate(
                now,
                properties.batchSize()
        );
        for (NotificationDelivery delivery : deliveries) {
            // 阶段 2：为本轮投递生成 lease owner token。
            // 每次 claim 生成新的 lease token：它是本轮租约的身份证，worker finalize 时据此确认"还是我持有"。
            // 用 UUID 而非 nextAttemptAt 时间戳，避免 TIMESTAMP(6) 微秒截断导致的内存/回读不相等误判。
            String leaseToken = UUID.randomUUID().toString();
            // 阶段 3：PENDING -> PROCESSING，在短事务内提交。
            // lease 先 commit，worker 才开始调 provider；worker 宕机时 recoverer 在 lease 到期后放回。
            delivery.markProcessing(now, properties.processingTimeoutSeconds(), leaseToken);
            deliveryRepository.updateDeliveryState(delivery);
        }
        return deliveries;
    }
}
