package com.minicard.statement.infrastructure.messaging;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.minicard.authorization.domain.Money;
import com.minicard.messaging.outbox.OutboxEvent;
import com.minicard.messaging.outbox.OutboxEventRepository;
import com.minicard.statement.domain.event.StatementClosedDomainEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class StatementOutboxAdapterTest {

    private static final Instant NOW = Instant.parse("2026-07-01T00:00:00Z");

    private final ObjectMapper objectMapper =
            new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void closedDomainEventWritesStatementClosedOutboxMessage() throws Exception {
        OutboxEventRepository repository = mock(OutboxEventRepository.class);
        StatementOutboxAdapter adapter = new StatementOutboxAdapter(
                repository,
                objectMapper,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
        UUID statementId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();

        adapter.append(new StatementClosedDomainEvent(
                statementId,
                accountId,
                LocalDate.parse("2026-06-01"),
                LocalDate.parse("2026-06-30"),
                LocalDate.parse("2026-07-25"),
                money("1500.00"),
                money("1000.00"),
                2,
                NOW
        ));

        ArgumentCaptor<OutboxEvent> event = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(repository).insert(event.capture());
        JsonNode envelope = objectMapper.readTree(event.getValue().payload());
        JsonNode payload = envelope.get("payload");
        assertThat(event.getValue().aggregateType()).isEqualTo("Statement");
        assertThat(event.getValue().aggregateId()).isEqualTo(statementId.toString());
        assertThat(event.getValue().partitionKey()).isEqualTo(accountId.toString());
        assertThat(event.getValue().eventType()).isEqualTo("statement.closed");
        assertThat(envelope.get("eventType").asText()).isEqualTo("statement.closed");
        assertThat(payload.get("statementId").asText()).isEqualTo(statementId.toString());
        assertThat(payload.get("creditAccountId").asText()).isEqualTo(accountId.toString());
        assertThat(payload.get("totalAmount").asText()).isEqualTo("1500.00");
        assertThat(payload.get("transactionCount").asInt()).isEqualTo(2);
    }

    private Money money(String amount) {
        return new Money(new BigDecimal(amount), Currency.getInstance("JPY"));
    }
}
