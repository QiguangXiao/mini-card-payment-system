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
 *
 * <p>recoverer 兜底的是"worker 在 claim 之后永远回不来"的时间线：</p>
 * <pre>
 * t0  worker claim:  PENDING -&gt; PROCESSING(token=X, lease deadline=t0+30s)
 * t1  pod 宕机/进程被 kill：finalize 永远不会执行，row 停在 PROCESSING
 * t2  (&gt;deadline) recoverer 扫描到超时 row -&gt; 按一次失败处理:
 *       attempts+1 &lt; maxAttempts -&gt; PENDING(nextAttemptAt=now+backoff)
 *       attempts+1 &gt;= maxAttempts -&gt; DEAD
 * t3  poller 下一轮重新 claim（生成新 token），publish 重新执行
 * </pre>
 *
 * <p>注意 t1 有两种可能：Kafka send 根本没发生，或 broker 已 ack 但 finalize 前宕机。
 * recoverer 无法区分，只能统一重发——所以 Outbox 是 at-least-once，t3 可能产生重复消息，
 * 由 consumer Inbox 按 eventId 去重。若 t1 的 worker 只是慢而非死，t3 之后它迟到 finalize
 * 会因 leaseToken 不匹配被拒（见 OutboxWorker 的 stale worker 时间线）。</p>
 */
@Component
// 发布恢复器和 poller 用同一个 outbox.publisher.enabled 开关，便于测试中关闭所有后台发布动作。
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
    // @Scheduled 指定 outboxTaskScheduler，避免 Outbox 恢复和 DelayJob/Statement batch 抢同一个默认调度线程。
    @Scheduled(
            fixedDelayString = "${outbox.publisher.recovery-fixed-delay-ms:5000}",
            scheduler = "outboxTaskScheduler"
    )
    // recoverer 自己开事务批量锁住 stuck rows；不要复用 worker 的 finalize 事务，否则扫描和单条处理会混在一起。
    @Transactional
    public void recoverStuckEvents() {
        Instant now = Instant.now(clock);
        // 阶段 1：扫描 publish lease 已超时的 PROCESSING events。
        // SKIP LOCKED 让多个 recoverer 实例不会互相等待或重复恢复同一行。
        List<OutboxEvent> events = outboxEventRepository.findStuckProcessingBatchForUpdate(
                now,
                properties.batchSize()
        );
        for (OutboxEvent event : events) {
            // 阶段 2：把超时 PROCESSING 当作一次 publish failure，推进 retry/DEAD。
            // 超时发布按一次失败处理；超过 maxAttempts 后转 DEAD，避免无限打 Kafka。
            // 如果没有 recoverer，pod 在 PROCESSING 后宕机的事件会永久不可见，可靠发布链路会断。
            event.markProcessingTimedOut(now, properties.maxAttempts());
            // 阶段 3：持久化恢复结果。回到 PENDING 的 event 会在下一轮 poll 中重新发布。
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
