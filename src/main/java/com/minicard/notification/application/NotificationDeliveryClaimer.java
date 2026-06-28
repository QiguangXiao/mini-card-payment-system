package com.minicard.notification.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

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

    @Transactional
    public List<NotificationDelivery> claimDispatchableDeliveries() {
        Instant now = Instant.now(clock);
        List<NotificationDelivery> deliveries = deliveryRepository.findDispatchableBatchForUpdate(
                now,
                properties.batchSize()
        );
        for (NotificationDelivery delivery : deliveries) {
            // lease 先 commit，worker 才开始调 provider；worker 宕机时 recoverer 在 lease 到期后放回。
            delivery.markProcessing(now, properties.processingTimeoutSeconds());
            deliveryRepository.updateDeliveryState(delivery);
        }
        return deliveries;
    }
}
