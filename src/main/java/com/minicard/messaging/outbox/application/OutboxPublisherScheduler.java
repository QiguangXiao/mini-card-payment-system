package com.minicard.messaging.outbox.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        prefix = "outbox.publisher",
        name = "enabled",
        havingValue = "true"
)
public class OutboxPublisherScheduler {

    private final OutboxPublisherService publisherService;

    public OutboxPublisherScheduler(OutboxPublisherService publisherService) {
        this.publisherService = publisherService;
    }

    @Scheduled(
            fixedDelayString = "${outbox.publisher.fixed-delay-ms:1000}",
            scheduler = "outboxTaskScheduler"
    )
    public void publishPendingEvents() {
        // @Scheduled 只负责周期性触发；claim/publish/finalize 的事务拆分由 service 内部控制。
        publisherService.publishBatch();
    }
}
