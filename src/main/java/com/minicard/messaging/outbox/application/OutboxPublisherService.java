package com.minicard.messaging.outbox.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import com.minicard.messaging.outbox.domain.OutboxEvent;
import com.minicard.messaging.outbox.domain.OutboxEventRepository;
import com.minicard.messaging.outbox.domain.OutboxEventStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 轮询 durable Outbox rows，并以 at-least-once 语义发布到 Kafka。
 *
 * <p>发布流程拆成 claim、publish、finalize 三段：DB row lock 只包住短事务，
 * Kafka ack 等待发生在事务外。即便如此，Kafka 和 MySQL 仍不能原子提交，
 * 所以消费者仍必须按 eventId 做幂等(idempotency)。</p>
 */
@Service
public class OutboxPublisherService {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisherService.class);

    private final OutboxEventRepository outboxEventRepository;
    private final OutboxMessagePublisher messagePublisher;
    private final OutboxPublisherProperties properties;
    private final Clock clock;
    private final TransactionOperations transactionOperations;

    public OutboxPublisherService(
            OutboxEventRepository outboxEventRepository,
            OutboxMessagePublisher messagePublisher,
            OutboxPublisherProperties properties,
            Clock clock,
            TransactionOperations transactionOperations
    ) {
        this.outboxEventRepository = outboxEventRepository;
        this.messagePublisher = messagePublisher;
        this.properties = properties;
        this.clock = clock;
        this.transactionOperations = transactionOperations;
    }

    public void publishBatch() {
        for (int i = 0; i < properties.batchSize(); i++) {
            // 第一步 claim：短事务内用 FOR UPDATE SKIP LOCKED 领取一条事件并设置 PROCESSING lease。
            OutboxEvent event = claimNextPublishable();
            if (event == null) {
                return;
            }
            try {
                // 第二步 publish：在事务外等待 Kafka acknowledgement，避免持有 DB row lock。
                messagePublisher.publish(
                        event,
                        Duration.ofMillis(properties.sendTimeoutMs())
                );
                // 第三步 finalize：短事务内把同一个 lease 标为 PUBLISHED。
                markPublished(event);
            } catch (RuntimeException exception) {
                // 失败不丢事件：markFailed() 会增加 attempts，并设置下一次 retry 时间或进入 DEAD。
                markFailed(
                        event,
                        exception.getMessage() == null
                                ? exception.getClass().getSimpleName()
                                : exception.getMessage(),
                        exception
                );
                // 遇到失败先停止本批次，避免后面的事件 overtaking 前面的事件。
                // 生产级高吞吐实现通常按 partition key 保序，而不是全局停止。
                break;
            }
        }
    }

    private OutboxEvent claimNextPublishable() {
        return transactionOperations.execute(status -> {
            Instant now = Instant.now(clock);
            OutboxEvent event = outboxEventRepository
                    .findNextPublishableForUpdate(now)
                    .orElse(null);
            if (event == null) {
                return null;
            }
            // PROCESSING lease 先 commit。publisher 宕机时，nextAttemptAt 到期后可重新 claim。
            event.markProcessing(now, properties.processingTimeoutSeconds());
            outboxEventRepository.updateDeliveryState(event);
            return event;
        });
    }

    private void markPublished(OutboxEvent claimedEvent) {
        transactionOperations.executeWithoutResult(status -> {
            OutboxEvent event = lockCurrentLease(claimedEvent);
            if (event == null) {
                return;
            }
            event.markPublished(Instant.now(clock));
            outboxEventRepository.updateDeliveryState(event);
            log.info(
                    "outbox_published eventId={} eventType={} aggregateId={}",
                    event.id(),
                    event.eventType(),
                    event.aggregateId()
            );
        });
    }

    private void markFailed(
            OutboxEvent claimedEvent,
            String error,
            RuntimeException exception
    ) {
        transactionOperations.executeWithoutResult(status -> {
            OutboxEvent event = lockCurrentLease(claimedEvent);
            if (event == null) {
                return;
            }
            event.markFailed(error, Instant.now(clock), properties.maxAttempts());
            outboxEventRepository.updateDeliveryState(event);
            log.warn(
                    "outbox_publish_failed eventId={} eventType={} attempts={} status={}",
                    event.id(),
                    event.eventType(),
                    event.attempts(),
                    event.status(),
                    exception
            );
        });
    }

    private OutboxEvent lockCurrentLease(OutboxEvent claimedEvent) {
        OutboxEvent event = outboxEventRepository.findByIdForUpdate(claimedEvent.id())
                .orElseThrow(() -> new IllegalStateException(
                        "claimed outbox event disappeared " + claimedEvent.id()
                ));
        if (event.status() != OutboxEventStatus.PROCESSING
                || !event.nextAttemptAt().equals(claimedEvent.nextAttemptAt())) {
            // lease token 不一致表示这条 event 已被别的 publisher 重新领取或完成；
            // 旧 publisher 的迟到结果不能覆盖当前状态。
            log.info(
                    "outbox_finalize_skipped_stale_lease eventId={} currentStatus={}",
                    event.id(),
                    event.status()
            );
            return null;
        }
        return event;
    }
}
