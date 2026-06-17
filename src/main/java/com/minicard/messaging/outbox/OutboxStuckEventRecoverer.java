package com.minicard.messaging.outbox;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 恢复长期停留在 PROCESSING 的 Outbox events。
 */
@Component
@ConditionalOnProperty(
        prefix = "outbox.publisher",
        name = "enabled",
        havingValue = "true"
)
public class OutboxStuckEventRecoverer {

    private static final Logger log = LoggerFactory.getLogger(OutboxStuckEventRecoverer.class);

    private final OutboxEventRepository outboxEventRepository;
    private final OutboxPublisherProperties properties;
    private final Clock clock;

    public OutboxStuckEventRecoverer(
            OutboxEventRepository outboxEventRepository,
            OutboxPublisherProperties properties,
            Clock clock
    ) {
        this.outboxEventRepository = outboxEventRepository;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(
            fixedDelayString = "${outbox.publisher.recovery-fixed-delay-ms:5000}",
            scheduler = "outboxTaskScheduler"
    )
    @Transactional
    public void recoverStuckEvents() {
        Instant now = Instant.now(clock);
        List<OutboxEvent> events = outboxEventRepository.findStuckProcessingBatchForUpdate(
                now,
                properties.batchSize()
        );
        for (OutboxEvent event : events) {
            event.markProcessingTimedOut(now, properties.maxAttempts());
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
