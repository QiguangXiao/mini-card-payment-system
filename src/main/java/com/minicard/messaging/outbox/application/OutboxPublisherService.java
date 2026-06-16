package com.minicard.messaging.outbox.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.minicard.messaging.outbox.domain.OutboxEvent;
import com.minicard.messaging.outbox.domain.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 轮询 durable Outbox rows，并以 at-least-once 语义发布到 Kafka。
 *
 * <p>DB lock 可以避免多个实例同时选中同一 row，但不能让 Kafka ack 和 MySQL commit 原子化。
 * 因此消费者仍必须按 eventId 做幂等(idempotency)。</p>
 */
@Service
public class OutboxPublisherService {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisherService.class);

    private final OutboxEventRepository outboxEventRepository;
    private final OutboxMessagePublisher messagePublisher;
    private final OutboxPublisherProperties properties;
    private final Clock clock;

    public OutboxPublisherService(
            OutboxEventRepository outboxEventRepository,
            OutboxMessagePublisher messagePublisher,
            OutboxPublisherProperties properties,
            Clock clock
    ) {
        this.outboxEventRepository = outboxEventRepository;
        this.messagePublisher = messagePublisher;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public void publishBatch() {
        Instant now = Instant.now(clock);
        // findPublishableBatchForUpdate() 使用 FOR UPDATE SKIP LOCKED，
        // 多个 pod 可以并发扫描 outbox，而不会同时发布同一批 row。
        List<OutboxEvent> events = outboxEventRepository.findPublishableBatchForUpdate(
                now,
                properties.batchSize()
        );

        for (OutboxEvent event : events) {
            try {
                // publish() 只负责把已持久化 payload 发给 broker；成功后再更新 DB delivery state。
                messagePublisher.publish(
                        event,
                        Duration.ofMillis(properties.sendTimeoutMs())
                );
                event.markPublished(Instant.now(clock));
                outboxEventRepository.updateDeliveryState(event);
                log.info(
                        "outbox_published eventId={} eventType={} aggregateId={}",
                        event.id(),
                        event.eventType(),
                        event.aggregateId()
                );
            } catch (RuntimeException exception) {
                // 失败不丢事件：markFailed() 会增加 attempts，并设置下一次 retry 时间或进入 DEAD。
                event.markFailed(
                        exception.getMessage() == null
                                ? exception.getClass().getSimpleName()
                                : exception.getMessage(),
                        Instant.now(clock),
                        properties.maxAttempts()
                );
                outboxEventRepository.updateDeliveryState(event);
                log.warn(
                        "outbox_publish_failed eventId={} eventType={} attempts={} status={}",
                        event.id(),
                        event.eventType(),
                        event.attempts(),
                        event.status(),
                        exception
                );

                // 遇到失败先停止本批次，避免后面的事件 overtaking 前面的事件。
                // 生产级高吞吐实现通常按 partition key 保序，而不是全局停止。
                break;
            }
        }
    }
}
