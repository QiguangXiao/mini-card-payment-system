package com.minicard.messaging.outbox;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 批量 claim 待发布 Outbox events。
 *
 * <p>关键词：事件领取, 行锁, PROCESSING lease, outbox claim,
 * FOR UPDATE SKIP LOCKED, short transaction, 発行取得(はっこうしゅとく),
 * 行ロック(ぎょうロック)。</p>
 *
 * <p>这个组件和 DelayJobClaimer 对称：只在短事务里把 due rows 从 PENDING
 * 改成 PROCESSING lease。Kafka publish 会在事务提交后由 OutboxWorker 完成，
 * 避免拿着 MySQL row lock 等 broker acknowledgement。</p>
 */
@Service
@RequiredArgsConstructor
public class OutboxClaimer {

    /** repository SQL 使用 FOR UPDATE SKIP LOCKED 领取可发布事件。 */
    private final OutboxEventRepository outboxEventRepository;
    /** 控制 batch size 和 lease timeout。 */
    private final OutboxProperties properties;
    /** 注入 clock 让测试可以固定 now。 */
    private final Clock clock;

    /**
     * 领取可发布事件并写入 PROCESSING lease。
     */
    @Transactional
    public List<OutboxEvent> claimPublishableEvents() {
        Instant now = Instant.now(clock);
        // findPublishableBatchForUpdate 在 SQL 层使用 FOR UPDATE SKIP LOCKED。
        List<OutboxEvent> events = outboxEventRepository.findPublishableBatchForUpdate(
                now,
                properties.batchSize()
        );
        for (OutboxEvent event : events) {
            // PROCESSING lease 先 commit，worker 才开始等 Kafka ack。
            // 如果 worker 宕机，recoverer 会在 lease deadline 后把 event 放回 retry。
            // 如果 claim 事务一直包着 Kafka publish，MySQL row lock 会被 broker latency 放大。
            event.markProcessing(now, properties.processingTimeoutSeconds());
            outboxEventRepository.updateDeliveryState(event);
        }
        return events;
    }
}
