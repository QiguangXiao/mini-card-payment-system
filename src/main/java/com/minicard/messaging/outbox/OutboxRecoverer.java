package com.minicard.messaging.outbox;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 恢复长期停留在 PROCESSING 的 Outbox events。
 *
 * <p>关键词：Outbox 恢复, 发布租约, 重试, outbox recovery,
 * processing lease, retry, アウトボックス復旧(アウトボックスふっきゅう),
 * 発行リース(はっこうリース)。</p>
 */
@Component
@ConditionalOnProperty(
        prefix = "outbox.publisher",
        name = "enabled",
        havingValue = "true"
)
@Slf4j
@RequiredArgsConstructor
public class OutboxRecoverer {

    /** 查询 lease 超时事件并更新 delivery state。 */
    private final OutboxEventRepository outboxEventRepository;
    /** batch size 和 maxAttempts。 */
    private final OutboxProperties properties;
    /** 当前时间来源。 */
    private final Clock clock;

    /**
     * 恢复卡在 PROCESSING 的 Outbox events。
     */
    @Scheduled(
            fixedDelayString = "${outbox.publisher.recovery-fixed-delay-ms:5000}",
            scheduler = "outboxTaskScheduler"
    )
    @Transactional
    public void recoverStuckEvents() {
        Instant now = Instant.now(clock);
        List<OutboxEvent> events = outboxEventRepository.findStuckProcessingBatchForUpdate(
                now,
                properties.batchSize()
        );
        for (OutboxEvent event : events) {
            // 超时发布按一次失败处理；超过 maxAttempts 后转 DEAD，避免无限打 Kafka。
            // 如果没有 recoverer，pod 在 PROCESSING 后宕机的事件会永久不可见，可靠发布链路会断。
            event.markProcessingTimedOut(now, properties.maxAttempts());
            outboxEventRepository.updateDeliveryState(event);
            log.warn(
                    "outbox_recovered eventId={} eventType={} attempts={} status={}",
                    event.id(),
                    event.eventType(),
                    event.attempts(),
                    event.status()
            );
        }
    }
}
