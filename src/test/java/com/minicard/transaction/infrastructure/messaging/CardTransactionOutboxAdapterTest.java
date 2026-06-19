package com.minicard.transaction.infrastructure.messaging;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.minicard.authorization.domain.Money;
import com.minicard.messaging.outbox.OutboxEvent;
import com.minicard.messaging.outbox.OutboxEventRepository;
import com.minicard.transaction.domain.event.CardTransactionPostedDomainEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class CardTransactionOutboxAdapterTest {

    private static final Instant NOW = Instant.parse("2026-06-20T00:00:00Z");

    private final ObjectMapper objectMapper =
            new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void postedDomainEventWritesCardTransactionPostedOutboxMessage() throws Exception {
        OutboxEventRepository repository = mock(OutboxEventRepository.class);
        CardTransactionOutboxAdapter adapter = new CardTransactionOutboxAdapter(
                repository,
                objectMapper,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
        UUID transactionId = UUID.randomUUID();
        UUID authorizationId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();

        adapter.append(new CardTransactionPostedDomainEvent(
                transactionId,
                "ntx-001",
                authorizationId,
                "card-123",
                accountId,
                money(),
                NOW
        ));

        ArgumentCaptor<OutboxEvent> event = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(repository).insert(event.capture());
        JsonNode envelope = objectMapper.readTree(event.getValue().payload());
        JsonNode payload = envelope.get("payload");
        assertThat(event.getValue().aggregateType()).isEqualTo("CardTransaction");
        assertThat(event.getValue().eventType()).isEqualTo("card_transaction.posted");
        assertThat(envelope.get("eventType").asText()).isEqualTo("card_transaction.posted");
        assertThat(payload.get("cardTransactionId").asText()).isEqualTo(transactionId.toString());
        assertThat(payload.get("authorizationId").asText()).isEqualTo(authorizationId.toString());
        assertThat(payload.get("postedAt").asText()).isEqualTo(NOW.toString());
    }

    private Money money() {
        return new Money(new BigDecimal("100.00"), Currency.getInstance("JPY"));
    }
}
