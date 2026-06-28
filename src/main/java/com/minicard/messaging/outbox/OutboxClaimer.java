package com.minicard.messaging.outbox;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

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
    // claim 必须是一个短事务：只锁定、改 PROCESSING lease、提交。
    // 如果把 Kafka publish 也放进这个事务，broker latency 会放大 MySQL row lock 时间。
    @Transactional
    public List<OutboxEvent> claimPublishableEvents() {
        Instant now = Instant.now(clock);
        // findPublishableBatchForUpdate 在 SQL 层使用 FOR UPDATE SKIP LOCKED。
        List<OutboxEvent> events = outboxEventRepository.findPublishableBatchForUpdate(
                now,
                properties.batchSize()
        );
        for (OutboxEvent event : events) {
            // 每次 claim 生成新的 lease token：它回答"本轮 owner 是谁"，而 nextAttemptAt 只回答"何时超时"。
            // 如果旧 worker 在 lease 过期后才回来，finalize 会因 token 不匹配而放弃覆盖。
            String leaseToken = UUID.randomUUID().toString();
            // PROCESSING lease 先 commit，worker 才开始等 Kafka ack。
            // 如果 worker 宕机，recoverer 会在 lease deadline 后把 event 放回 retry。
            // 如果 claim 事务一直包着 Kafka publish，MySQL row lock 会被 broker latency 放大。
            event.markProcessing(now, properties.processingTimeoutSeconds(), leaseToken);
            outboxEventRepository.updateDeliveryState(event);
        }
        return events;
    }
}
