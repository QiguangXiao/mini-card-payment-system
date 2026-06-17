package com.minicard.messaging.outbox.application;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.minicard.messaging.outbox.domain.OutboxEvent;
import com.minicard.messaging.outbox.domain.OutboxEventRepository;
import com.minicard.messaging.outbox.domain.OutboxEventStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxClaimServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-16T00:00:00Z");

    @Test
    void claimsPublishableEventsInShortTransaction() {
        OutboxEventRepository repository = mock(OutboxEventRepository.class);
        OutboxEvent event = pendingEvent();
        when(repository.findPublishableBatchForUpdate(NOW, 10)).thenReturn(List.of(event));
        OutboxClaimService service = new OutboxClaimService(
                repository,
                properties(),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );

        List<OutboxEvent> claimed = service.claimPublishableEvents();

        assertThat(claimed).containsExactly(event);
        assertThat(event.status()).isEqualTo(OutboxEventStatus.PROCESSING);
        assertThat(event.nextAttemptAt()).isEqualTo(NOW.plusSeconds(30));
        verify(repository).updateDeliveryState(event);
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
}
