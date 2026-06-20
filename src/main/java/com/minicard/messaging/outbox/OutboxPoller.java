package com.minicard.messaging.outbox;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

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

    /** 查询可发布事件并更新 delivery state。 */
    private final OutboxEventRepository outboxEventRepository;
    /** Outbox batch size、lease timeout 和 worker 配置。 */
    private final OutboxProperties properties;
    /** 可测试的当前时间来源。 */
    private final Clock clock;
    /** publish + finalize 的 worker。 */
    private final OutboxWorker worker;
    /** Kafka publish worker pool。 */
    private final TaskExecutor outboxWorkerExecutor;
    /** 显式短事务 claim，避免 @Scheduled self-invocation 陷阱。 */
    private final TransactionOperations transactionOperations;

    public OutboxPoller(
            OutboxEventRepository outboxEventRepository,
            OutboxProperties properties,
            Clock clock,
            OutboxWorker worker,
            @Qualifier("outboxWorkerExecutor") TaskExecutor outboxWorkerExecutor,
            TransactionOperations transactionOperations
    ) {
        this.outboxEventRepository = outboxEventRepository;
        this.properties = properties;
        this.clock = clock;
        this.worker = worker;
        this.outboxWorkerExecutor = outboxWorkerExecutor;
        this.transactionOperations = transactionOperations;
    }

    /**
     * 扫描可发布事件并提交给 worker。
     */
    @Scheduled(
            fixedDelayString = "${outbox.publisher.fixed-delay-ms:1000}",
            scheduler = "outboxTaskScheduler"
    )
    public void pollPublishableEvents() {
        List<OutboxEvent> events = claimPublishableEvents();
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

    List<OutboxEvent> claimPublishableEvents() {
        return transactionOperations.execute(status -> {
            Instant now = Instant.now(clock);
            // findPublishableBatchForUpdate 在 SQL 层使用 FOR UPDATE SKIP LOCKED。
            List<OutboxEvent> events = outboxEventRepository.findPublishableBatchForUpdate(
                    now,
                    properties.batchSize()
            );
            for (OutboxEvent event : events) {
                // PROCESSING lease 先 commit，worker 才开始等 Kafka ack。
                // 如果 worker 宕机，recoverer 会在 lease deadline 后把 event 放回 retry。
                event.markProcessing(now, properties.processingTimeoutSeconds());
                outboxEventRepository.updateDeliveryState(event);
            }
            return events;
        });
    }
}
