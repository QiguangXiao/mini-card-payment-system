package com.minicard.messaging.outbox.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.minicard.messaging.outbox.domain.OutboxEvent;
import com.minicard.messaging.outbox.domain.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OutboxPublisherService {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisherService.class);

    private final OutboxEventRepository outboxEventRepository;
    private final OutboxMessagePublisher messagePublisher;
    private final OutboxPublisherProperties properties;
    private final Clock clock;

    public OutboxPublisherService(
            OutboxEventRepository outboxEventRepository,
            OutboxMessagePublisher messagePublisher,
            OutboxPublisherProperties properties,
            Clock clock
    ) {
        this.outboxEventRepository = outboxEventRepository;
        this.messagePublisher = messagePublisher;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public void publishBatch() {
        Instant now = Instant.now(clock);
        List<OutboxEvent> events = outboxEventRepository.findPublishableBatchForUpdate(
                now,
                properties.batchSize()
        );

        for (OutboxEvent event : events) {
            try {
                messagePublisher.publish(
                        event,
                        Duration.ofMillis(properties.sendTimeoutMs())
                );
                event.markPublished(Instant.now(clock));
                outboxEventRepository.updateDeliveryState(event);
                log.info(
                        "outbox_published eventId={} eventType={} aggregateId={}",
                        event.id(),
                        event.eventType(),
                        event.aggregateId()
                );
            } catch (RuntimeException exception) {
                event.markFailed(
                        exception.getMessage() == null
                                ? exception.getClass().getSimpleName()
                                : exception.getMessage(),
                        Instant.now(clock),
                        properties.maxAttempts()
                );
                outboxEventRepository.updateDeliveryState(event);
                log.warn(
                        "outbox_publish_failed eventId={} eventType={} attempts={} status={}",
                        event.id(),
                        event.eventType(),
                        event.attempts(),
                        event.status(),
                        exception
                );

                // Stop after a failure so later events do not overtake an older
                // event. A production high-throughput publisher may preserve
                // ordering per partition key instead of stopping globally.
                break;
            }
        }
    }
}
