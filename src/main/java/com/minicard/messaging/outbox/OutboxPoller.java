package com.minicard.messaging.outbox;

import java.util.List;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Outbox poller，只负责 poll、claim、submit worker。
 *
 * <p>关键词：Outbox 扫描, 发布领取, worker pool, outbox poller,
 * reliable publication, task rejection, アウトボックスポーラー,
 * 確実発行(かくじつはっこう)。</p>
 *
 * <p>它不直接发送 Kafka，也不提前 finalize。每个 worker 必须自己 publish + markPublished/markFailed。</p>
 */
@Component
@ConditionalOnProperty(
        prefix = "outbox.publisher",
        name = "enabled",
        havingValue = "true"
)
@Slf4j
public class OutboxPoller {

    /** 短事务 claim 组件，负责 PENDING -> PROCESSING lease。 */
    private final OutboxClaimer claimer;
    /** publish + finalize 的 worker。 */
    private final OutboxWorker worker;
    /** Kafka publish worker pool。 */
    private final TaskExecutor outboxWorkerExecutor;

    public OutboxPoller(
            OutboxClaimer claimer,
            OutboxWorker worker,
            @Qualifier("outboxWorkerExecutor") TaskExecutor outboxWorkerExecutor
    ) {
        this.claimer = claimer;
        this.worker = worker;
        this.outboxWorkerExecutor = outboxWorkerExecutor;
    }

    /**
     * 扫描可发布事件并提交给 worker。
     */
    @Scheduled(
            fixedDelayString = "${outbox.publisher.fixed-delay-ms:1000}",
            scheduler = "outboxTaskScheduler"
    )
    public void pollPublishableEvents() {
        List<OutboxEvent> events = claimer.claimPublishableEvents();
        for (OutboxEvent event : events) {
            try {
                // claim commit 后才提交 worker。worker 会在 finalize 前重新校验 PROCESSING lease。
                outboxWorkerExecutor.execute(() -> worker.publishClaimedEvent(event));
            } catch (TaskRejectedException exception) {
                worker.markRejectedForRetry(event, exception);
                log.warn("outbox_worker_rejected eventId={}", event.id(), exception);
            }
        }
    }
}
