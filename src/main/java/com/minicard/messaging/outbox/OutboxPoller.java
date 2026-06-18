package com.minicard.messaging.outbox;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * <p>它不直接发送 Kafka，也不提前 finalize。每个 worker 必须自己 publish + markPublished/markFailed。</p>
 */
@Component
@ConditionalOnProperty(
        prefix = "outbox.publisher",
        name = "enabled",
        havingValue = "true"
)
public class OutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);

    private final OutboxEventRepository outboxEventRepository;
    private final OutboxProperties properties;
    private final Clock clock;
    private final OutboxWorker worker;
    private final TaskExecutor outboxWorkerExecutor;
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
