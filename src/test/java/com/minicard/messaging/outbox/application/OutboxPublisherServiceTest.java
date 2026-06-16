package com.minicard.messaging.outbox.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import com.minicard.messaging.outbox.domain.OutboxEvent;
import com.minicard.messaging.outbox.domain.OutboxEventRepository;
import com.minicard.messaging.outbox.domain.OutboxEventStatus;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxPublisherServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-16T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final OutboxPublisherProperties PROPERTIES =
            new OutboxPublisherProperties(true, 1000, 10, 5000, 30, 3);

    @Test
    void publishesOutsideClaimTransactionAndMarksEventPublished() {
        InMemoryOutboxEventRepository repository = new InMemoryOutboxEventRepository(
                pendingEvent()
        );
        RecordingPublisher publisher = new RecordingPublisher();
        OutboxPublisherService service = new OutboxPublisherService(
                repository,
                publisher,
                PROPERTIES,
                CLOCK,
                immediateTransactions()
        );

        service.publishBatch();

        assertThat(publisher.publishedStatus).isEqualTo(OutboxEventStatus.PROCESSING);
        assertThat(repository.event().status()).isEqualTo(OutboxEventStatus.PUBLISHED);
        assertThat(repository.event().publishedAt()).isEqualTo(NOW);
    }

    @Test
    void failedPublicationReturnsEventToPendingWithBackoff() {
        InMemoryOutboxEventRepository repository = new InMemoryOutboxEventRepository(
                pendingEvent()
        );
        OutboxPublisherService service = new OutboxPublisherService(
                repository,
                (event, timeout) -> {
                    throw new IllegalStateException("Kafka unavailable");
                },
                PROPERTIES,
                CLOCK,
                immediateTransactions()
        );

        service.publishBatch();

        assertThat(repository.event().status()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(repository.event().attempts()).isEqualTo(1);
        assertThat(repository.event().nextAttemptAt()).isEqualTo(NOW.plusSeconds(1));
        assertThat(repository.event().lastError()).isEqualTo("Kafka unavailable");
    }

    @Test
    void stalePublisherResultDoesNotOverrideNewerLease() {
        InMemoryOutboxEventRepository repository = new InMemoryOutboxEventRepository(
                pendingEvent()
        );
        OutboxPublisherService service = new OutboxPublisherService(
                repository,
                (event, timeout) -> repository.reclaimWithNewLease(NOW.plusSeconds(31)),
                PROPERTIES,
                CLOCK,
                immediateTransactions()
        );

        service.publishBatch();

        assertThat(repository.event().status()).isEqualTo(OutboxEventStatus.PROCESSING);
        assertThat(repository.event().publishedAt()).isNull();
        assertThat(repository.event().nextAttemptAt()).isEqualTo(NOW.plusSeconds(61));
    }

    private static OutboxEvent pendingEvent() {
        return OutboxEvent.pending(
                UUID.randomUUID(),
                "Authorization",
                UUID.randomUUID().toString(),
                "authorization.decided",
                1,
                UUID.randomUUID().toString(),
                "{\"eventType\":\"authorization.decided\"}",
                NOW
        );
    }

    private static TransactionOperations immediateTransactions() {
        return new TransactionOperations() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(new SimpleTransactionStatus());
            }
        };
    }

    private static final class RecordingPublisher implements OutboxMessagePublisher {

        private OutboxEventStatus publishedStatus;

        @Override
        public void publish(OutboxEvent event, Duration timeout) {
            // Service 应该已经提交 claim transaction，并把事件标记为 PROCESSING 后才调用 publisher。
            publishedStatus = event.status();
        }
    }

    private static final class InMemoryOutboxEventRepository implements OutboxEventRepository {

        private OutboxEvent event;

        private InMemoryOutboxEventRepository(OutboxEvent event) {
            this.event = copy(event);
        }

        @Override
        public void insert(OutboxEvent event) {
            this.event = copy(event);
        }

        @Override
        public Optional<OutboxEvent> findNextPublishableForUpdate(Instant now) {
            if ((event.status() == OutboxEventStatus.PENDING
                    || event.status() == OutboxEventStatus.PROCESSING)
                    && !event.nextAttemptAt().isAfter(now)) {
                return Optional.of(copy(event));
            }
            return Optional.empty();
        }

        @Override
        public Optional<OutboxEvent> findByIdForUpdate(UUID id) {
            return event.id().equals(id)
                    ? Optional.of(copy(event))
                    : Optional.empty();
        }

        @Override
        public void updateDeliveryState(OutboxEvent event) {
            this.event = copy(event);
        }

        private OutboxEvent event() {
            return copy(event);
        }

        private void reclaimWithNewLease(Instant startedAt) {
            OutboxEvent reclaimed = copy(event);
            reclaimed.markProcessing(startedAt, PROPERTIES.processingTimeoutSeconds());
            event = copy(reclaimed);
        }

        private static OutboxEvent copy(OutboxEvent source) {
            return OutboxEvent.restore(
                    source.id(),
                    source.aggregateType(),
                    source.aggregateId(),
                    source.eventType(),
                    source.eventVersion(),
                    source.partitionKey(),
                    source.payload(),
                    source.status(),
                    source.attempts(),
                    source.nextAttemptAt(),
                    source.createdAt(),
                    source.publishedAt(),
                    source.lastError()
            );
        }
    }
}
