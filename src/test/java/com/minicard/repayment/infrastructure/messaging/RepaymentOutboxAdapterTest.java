package com.minicard.repayment.infrastructure.messaging;

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
import com.minicard.repayment.domain.event.RepaymentReceivedDomainEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class RepaymentOutboxAdapterTest {

    private static final Instant NOW = Instant.parse("2026-07-10T00:00:00Z");

    private final ObjectMapper objectMapper =
            new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void receivedDomainEventWritesRepaymentReceivedOutboxMessage() throws Exception {
        OutboxEventRepository repository = mock(OutboxEventRepository.class);
        RepaymentOutboxAdapter adapter = new RepaymentOutboxAdapter(
                repository,
                objectMapper,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
        UUID repaymentId = UUID.randomUUID();
        UUID statementId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();

        adapter.append(new RepaymentReceivedDomainEvent(
                repaymentId,
                statementId,
                accountId,
                money("500.00"),
                money("500.00"),
                money("1000.00"),
                NOW
        ));

        ArgumentCaptor<OutboxEvent> event = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(repository).insert(event.capture());
        JsonNode envelope = objectMapper.readTree(event.getValue().payload());
        JsonNode payload = envelope.get("payload");
        assertThat(event.getValue().aggregateType()).isEqualTo("Repayment");
        assertThat(event.getValue().aggregateId()).isEqualTo(repaymentId.toString());
        assertThat(event.getValue().eventType()).isEqualTo("repayment.received");
        assertThat(event.getValue().partitionKey()).isEqualTo(accountId.toString());
        assertThat(envelope.get("eventType").asText()).isEqualTo("repayment.received");
        assertThat(payload.get("repaymentId").asText()).isEqualTo(repaymentId.toString());
        assertThat(payload.get("statementId").asText()).isEqualTo(statementId.toString());
        assertThat(payload.get("creditAccountId").asText()).isEqualTo(accountId.toString());
        assertThat(payload.get("statementRemainingAmount").asText()).isEqualTo("1000.00");
        assertThat(payload.get("receivedAt").asText()).isEqualTo(NOW.toString());
    }

    private Money money(String amount) {
        return new Money(new BigDecimal(amount), Currency.getInstance("JPY"));
    }
}
