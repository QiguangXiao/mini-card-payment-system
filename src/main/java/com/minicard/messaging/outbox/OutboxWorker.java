package com.minicard.messaging.outbox;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Outbox worker，负责等待 Kafka acknowledgement，并由 worker 自己 finalize delivery state。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class OutboxWorker {

    private final OutboxEventRepository outboxEventRepository;
    private final OutboxMessagePublisher messagePublisher;
    private final OutboxProperties properties;
    private final Clock clock;
    private final TransactionOperations transactionOperations;

    public void publishClaimedEvent(OutboxEvent claimedEvent) {
        try {
            // publish() 会等待 broker acknowledgement。成功后才能 markPublished。
            messagePublisher.publish(
                    claimedEvent,
                    Duration.ofMillis(properties.sendTimeoutMs())
            );
            markPublished(claimedEvent);
        } catch (RuntimeException exception) {
            markFailed(
                    claimedEvent,
                    exception.getMessage() == null
                            ? exception.getClass().getSimpleName()
                            : exception.getMessage(),
                    exception
            );
        }
    }

    public void markRejectedForRetry(OutboxEvent claimedEvent, RuntimeException exception) {
        markFailed(claimedEvent, "outbox worker pool rejected event", exception);
    }

    private void markPublished(OutboxEvent claimedEvent) {
        transactionOperations.executeWithoutResult(status -> {
            OutboxEvent event = lockCurrentLease(claimedEvent);
            if (event == null) {
                return;
            }
            event.markPublished(Instant.now(clock));
            outboxEventRepository.updateDeliveryState(event);
            log.info(
                    "outbox_published eventId={} eventType={} aggregateId={}",
                    event.id(),
                    event.eventType(),
                    event.aggregateId()
            );
        });
    }

    private void markFailed(
            OutboxEvent claimedEvent,
            String error,
            RuntimeException exception
    ) {
        transactionOperations.executeWithoutResult(status -> {
            OutboxEvent event = lockCurrentLease(claimedEvent);
            if (event == null) {
                return;
            }
            event.markFailed(error, Instant.now(clock), properties.maxAttempts());
            outboxEventRepository.updateDeliveryState(event);
            log.warn(
                    "outbox_publish_failed eventId={} eventType={} attempts={} status={}",
                    event.id(),
                    event.eventType(),
                    event.attempts(),
                    event.status(),
                    exception
            );
        });
    }

    private OutboxEvent lockCurrentLease(OutboxEvent claimedEvent) {
        OutboxEvent event = outboxEventRepository.findByIdForUpdate(claimedEvent.id())
                .orElseThrow(() -> new IllegalStateException(
                        "claimed outbox event disappeared " + claimedEvent.id()
                ));
        if (event.status() != OutboxEventStatus.PROCESSING
                || !event.nextAttemptAt().equals(claimedEvent.nextAttemptAt())) {
            log.warn(
                    "outbox_lease_changed eventId={} claimedLease={} currentStatus={} currentLease={}",
                    claimedEvent.id(),
                    claimedEvent.nextAttemptAt(),
                    event.status(),
                    event.nextAttemptAt()
            );
            return null;
        }
        return event;
    }
}
