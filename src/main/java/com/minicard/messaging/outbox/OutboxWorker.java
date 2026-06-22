package com.minicard.messaging.outbox;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Outbox worker，负责等待 Kafka acknowledgement，并由 worker 自己 finalize delivery state。
 *
 * <p>关键词：消息发布, ack 等待, lease 校验, outbox worker,
 * Kafka acknowledgement, finalize, 発行ワーカー(はっこうワーカー),
 * リース検証(リースけんしょう)。</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class OutboxWorker {

    /** finalize 前会重新锁住 outbox row。 */
    private final OutboxEventRepository outboxEventRepository;
    /** Kafka adapter。 */
    private final OutboxMessagePublisher messagePublisher;
    /** send timeout 和 retry policy。 */
    private final OutboxProperties properties;
    /** 当前时间来源。 */
    private final Clock clock;
    /** 显式事务工具，确保 publish 与 finalize 分开。 */
    private final TransactionOperations transactionOperations;

    /**
     * 发布已领取事件，并根据结果 finalize。
     */
    public void publishClaimedEvent(OutboxEvent claimedEvent) {
        try {
            // publish() 会等待 broker acknowledgement。成功后才能 markPublished。
            // 如果不等 ack 就标 PUBLISHED，broker 实际失败时事件会永久丢失。
            messagePublisher.publish(
                    claimedEvent,
                    Duration.ofMillis(properties.sendTimeoutMs())
            );
            markPublished(claimedEvent);
        } catch (RuntimeException exception) {
            markFailed(
                    claimedEvent,
                    exception.getMessage() == null
                            ? exception.getClass().getSimpleName()
                            : exception.getMessage(),
                    exception
            );
        }
    }

    public void markRejectedForRetry(OutboxEvent claimedEvent, RuntimeException exception) {
        // worker pool 拒绝时，必须把 PROCESSING 事件放回 retry/DEAD，避免 lease 到期前一直不可见。
        // 如果直接吞掉 rejection，事件会停在 PROCESSING，直到 recoverer 扫描前下游都收不到消息。
        markFailed(claimedEvent, "outbox worker pool rejected event", exception);
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
            // 老 worker 返回太晚时不能覆盖新 lease 的处理结果，这是防并发覆盖的关键保护。
            // 如果不比较 lease token，旧 worker 可能把新 worker 已失败/重试的结果改成 PUBLISHED。
            log.warn(
                    "outbox_lease_changed eventId={} claimedLease={} currentStatus={} currentLease={}",
                    claimedEvent.id(),
                    claimedEvent.nextAttemptAt(),
                    event.status(),
                    event.nextAttemptAt()
            );
            return null;
        }
        return event;
    }
}
