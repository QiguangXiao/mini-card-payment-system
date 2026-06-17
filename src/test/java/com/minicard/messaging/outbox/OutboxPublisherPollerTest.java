package com.minicard.messaging.outbox;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxPublisherPollerTest {

    private static final Instant NOW = Instant.parse("2026-06-16T00:00:00Z");

    @Test
    void claimsEventsAndSubmitsThemToWorkerExecutor() {
        OutboxEventRepository repository = mock(OutboxEventRepository.class);
        OutboxWorker worker = mock(OutboxWorker.class);
        OutboxEvent event = pendingEvent();
        when(repository.findPublishableBatchForUpdate(NOW, 10)).thenReturn(List.of(event));
        OutboxPublisherPoller poller = new OutboxPublisherPoller(
                repository,
                properties(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                worker,
                new SyncTaskExecutor(),
                immediateTransactions()
        );

        poller.pollPublishableEvents();

        assertThat(event.status()).isEqualTo(OutboxEventStatus.PROCESSING);
        assertThat(event.nextAttemptAt()).isEqualTo(NOW.plusSeconds(30));
        verify(repository).updateDeliveryState(event);
        verify(worker).publishClaimedEvent(event);
    }

    private OutboxEvent pendingEvent() {
        return OutboxEvent.pending(
                UUID.randomUUID(),
                "Authorization",
                UUID.randomUUID().toString(),
                "authorization.approved",
                1,
                UUID.randomUUID().toString(),
                "{\"eventType\":\"authorization.approved\"}",
                NOW
        );
    }

    private OutboxPublisherProperties properties() {
        return new OutboxPublisherProperties(true, 1000, 5000, 10, 5000, 30, 3, 4, 100);
    }

    private TransactionOperations immediateTransactions() {
        return new TransactionOperations() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(new SimpleTransactionStatus());
            }
        };
    }
}
