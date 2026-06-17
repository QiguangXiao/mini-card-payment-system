package com.minicard.messaging.outbox;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxWorkerTest {

    private static final Instant NOW = Instant.parse("2026-06-16T00:00:00Z");

    @Test
    void publishesAndMarksEventPublished() {
        OutboxEventRepository repository = mock(OutboxEventRepository.class);
        RecordingPublisher publisher = new RecordingPublisher();
        OutboxEvent event = claimedEvent();
        when(repository.findByIdForUpdate(event.id())).thenReturn(Optional.of(event));
        OutboxWorker worker = worker(repository, publisher);

        worker.publishClaimedEvent(event);

        assertThat(publisher.publishedStatus).isEqualTo(OutboxEventStatus.PROCESSING);
        assertThat(event.status()).isEqualTo(OutboxEventStatus.PUBLISHED);
        assertThat(event.publishedAt()).isEqualTo(NOW);
        verify(repository).updateDeliveryState(event);
    }

    @Test
    void failedPublicationReturnsEventToPendingWithBackoff() {
        OutboxEventRepository repository = mock(OutboxEventRepository.class);
        OutboxEvent event = claimedEvent();
        when(repository.findByIdForUpdate(event.id())).thenReturn(Optional.of(event));
        OutboxWorker worker = worker(repository, (outboxEvent, timeout) -> {
            throw new IllegalStateException("Kafka unavailable");
        });

        worker.publishClaimedEvent(event);

        assertThat(event.status()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(event.attempts()).isEqualTo(1);
        assertThat(event.nextAttemptAt()).isEqualTo(NOW.plusSeconds(1));
        assertThat(event.lastError()).isEqualTo("Kafka unavailable");
        verify(repository).updateDeliveryState(event);
    }

    @Test
    void staleWorkerResultDoesNotOverrideNewerLease() {
        OutboxEventRepository repository = mock(OutboxEventRepository.class);
        OutboxEvent claimed = claimedEvent();
        OutboxEvent current = copy(claimed);
        current.markProcessing(NOW.plusSeconds(1), 30);
        when(repository.findByIdForUpdate(claimed.id())).thenReturn(Optional.of(current));
        OutboxWorker worker = worker(repository, new RecordingPublisher());

        worker.publishClaimedEvent(claimed);

        verify(repository, never()).updateDeliveryState(current);
    }

    private OutboxWorker worker(
            OutboxEventRepository repository,
            OutboxMessagePublisher publisher
    ) {
        return new OutboxWorker(
                repository,
                publisher,
                properties(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                immediateTransactions()
        );
    }

    private OutboxEvent claimedEvent() {
        OutboxEvent event = OutboxEvent.pending(
                UUID.randomUUID(),
                "Authorization",
                UUID.randomUUID().toString(),
                "authorization.approved",
                1,
                UUID.randomUUID().toString(),
                "{\"eventType\":\"authorization.approved\"}",
                NOW
        );
        event.markProcessing(NOW, 30);
        return event;
    }

    private OutboxPublisherProperties properties() {
        return new OutboxPublisherProperties(true, 1000, 5000, 10, 5000, 30, 3, 4, 100);
    }

    private OutboxEvent copy(OutboxEvent source) {
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

    private TransactionOperations immediateTransactions() {
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
            publishedStatus = event.status();
        }
    }
}
