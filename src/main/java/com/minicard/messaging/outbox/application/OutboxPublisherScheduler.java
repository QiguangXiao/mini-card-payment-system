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

    @Scheduled(fixedDelayString = "${outbox.publisher.fixed-delay-ms:1000}")
    public void publishPendingEvents() {
        // Scheduling and transaction handling live on separate Spring beans so
        // the @Transactional proxy on publishBatch() is actually applied.
        publisherService.publishBatch();
    }
}
