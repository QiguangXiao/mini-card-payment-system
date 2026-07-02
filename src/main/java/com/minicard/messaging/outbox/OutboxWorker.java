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
 *
 * <p>流程与 DelayJobWorker 对称：claimer 先在短事务内把 PENDING event 改成 PROCESSING lease；
 * worker 在事务外等待 Kafka ack；最后再开短事务校验 lease 并写 PUBLISHED/PENDING/DEAD。
 * 这样不会因为等 broker 回包而长时间占用 outbox row lock。</p>
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
            // 第一步：把已 claim 的 event 发给 Kafka，并等待 broker ack。
            // claimedEvent 只是本轮 lease 的快照；真正的最终状态稍后还要回 DB 校验。
            // publish() 会等待 broker acknowledgement。成功后才能 markPublished。
            // 如果不等 ack 就标 PUBLISHED，broker 实际失败时事件会永久丢失。
            messagePublisher.publish(
                    claimedEvent,
                    Duration.ofMillis(properties.sendTimeoutMs())
            );
            // 第二步：Kafka 已确认写入后，才尝试把 outbox row finalize 为 PUBLISHED。
            // 如果此时 lease 已变，lockCurrentLease 会跳过，避免旧 worker 覆盖新处理结果。
            markPublished(claimedEvent);
        } catch (RuntimeException exception) {
            // 第三步：发送失败也要持久化 retry/backoff 信息。
            // 如果只把异常打日志，event 会停留在 PROCESSING，直到 recoverer 超时才重新可见。
            markFailed(
                    claimedEvent,
                    exception.getMessage() == null
                            ? exception.getClass().getSimpleName()
                            : exception.getMessage(),
                    exception
            );
        }
    }

    /**
     * worker pool 没能接收任务时，把已领取事件放回 retry/DEAD 状态机。
     */
    public void markRejectedForRetry(OutboxEvent claimedEvent, RuntimeException exception) {
        // worker pool 拒绝时，必须把 PROCESSING 事件放回 retry/DEAD，避免 lease 到期前一直不可见。
        // 如果直接吞掉 rejection，事件会停在 PROCESSING，直到 recoverer 扫描前下游都收不到消息。
        markFailed(claimedEvent, "outbox worker pool rejected event", exception);
    }

    /**
     * 在独立短事务中重新校验 lease，并把 Kafka ack 后的事件标记为 PUBLISHED。
     */
    private void markPublished(OutboxEvent claimedEvent) {
        // finalize 使用 TransactionOperations，而不是在 publishClaimedEvent 外层包 @Transactional。
        // 这样等待 Kafka ack 的时间不会占用 DB transaction，只在更新 delivery state 时短暂开事务。
        transactionOperations.executeWithoutResult(status -> {
            OutboxEvent event = lockCurrentLease(claimedEvent);
            if (event == null) {
                return;
            }
            // markPublished() 是 Outbox 的成功终态：代表 Kafka broker 已 ack。
            // 它不是 consumer 已处理完成；consumer 仍要靠 Inbox 做 at-least-once 去重。
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

    /**
     * 在独立短事务中记录发布失败，推进 attempts、lastError 和下一次 retry 时间。
     */
    private void markFailed(
            OutboxEvent claimedEvent,
            String error,
            RuntimeException exception
    ) {
        // 失败 finalize 也要独立事务提交；否则 publish exception 被抛出后，失败次数和 nextAttemptAt 不会落库。
        transactionOperations.executeWithoutResult(status -> {
            OutboxEvent event = lockCurrentLease(claimedEvent);
            if (event == null) {
                return;
            }
            // markFailed() 会记录失败原因并计算下一次 publish 时间；达到 maxAttempts 后进入 DEAD。
            // 这样 poison event 不会无限压住 worker，也保留人工排查的 lastError。
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

    /**
     * 重新锁定当前 outbox row 并确认本 worker 仍持有 lease。
     */
    private OutboxEvent lockCurrentLease(OutboxEvent claimedEvent) {
        // publish Kafka ack 发生在事务外；回来 finalize 前必须重读当前 row 并加 FOR UPDATE。
        // 否则 recoverer 或新 worker 已接管时，旧 worker 仍可能用过期快照标 PUBLISHED/FAILED。
        OutboxEvent event = outboxEventRepository.findByIdForUpdate(claimedEvent.id())
                .orElseThrow(() -> new IllegalStateException(
                        "claimed outbox event disappeared " + claimedEvent.id()
        ));
        // 条件语义和 DelayJobWorker 一样：
        // status 必须仍是 PROCESSING；claimedEvent 必须有本轮 claim 的 leaseToken；
        // DB 当前 token 必须等于 claimed token。nextAttemptAt 只是 deadline，不是 owner identity。
        if (event.status() != OutboxEventStatus.PROCESSING
                || claimedEvent.leaseToken() == null
                || !claimedEvent.leaseToken().equals(event.leaseToken())) {
            // lease 已变时跳过 finalize：新 lease 的 worker/recoverer 才有权决定这条 outbox event 的后续状态。
            log.warn(
                    "outbox_lease_changed eventId={} claimedToken={} currentStatus={} currentToken={} currentLease={}",
                    claimedEvent.id(),
                    claimedEvent.leaseToken(),
                    event.status(),
                    event.leaseToken(),
                    event.nextAttemptAt()
            );
            return null;
        }
        return event;
    }
}
