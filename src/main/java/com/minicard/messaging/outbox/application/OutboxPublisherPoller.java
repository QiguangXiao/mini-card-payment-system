package com.minicard.messaging.outbox.application;

import java.util.List;

import com.minicard.messaging.outbox.domain.OutboxEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

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
public class OutboxPublisherPoller {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisherPoller.class);

    private final OutboxClaimService claimService;
    private final OutboxWorker worker;
    private final TaskExecutor outboxWorkerExecutor;

    public OutboxPublisherPoller(
            OutboxClaimService claimService,
            OutboxWorker worker,
            @Qualifier("outboxWorkerExecutor") TaskExecutor outboxWorkerExecutor
    ) {
        this.claimService = claimService;
        this.worker = worker;
        this.outboxWorkerExecutor = outboxWorkerExecutor;
    }

    @Scheduled(
            fixedDelayString = "${outbox.publisher.fixed-delay-ms:1000}",
            scheduler = "outboxTaskScheduler"
    )
    public void pollPublishableEvents() {
        List<OutboxEvent> events = claimService.claimPublishableEvents();
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
