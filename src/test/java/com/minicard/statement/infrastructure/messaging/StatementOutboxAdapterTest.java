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
import com.minicard.shared.domain.Money;
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
                LocalDate.parse("2026-07-27"),
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
        assertThat(event.getValue().eventType()).isEqualTo("statement.closed");
        // partition key 用 creditAccountId，保证同一账户的账单事件有序消费。
        assertThat(event.getValue().partitionKey()).isEqualTo(accountId.toString());
        assertThat(envelope.get("eventType").asText()).isEqualTo("statement.closed");
        assertThat(payload.get("statementId").asText()).isEqualTo(statementId.toString());
        assertThat(payload.get("creditAccountId").asText()).isEqualTo(accountId.toString());
        assertThat(payload.get("periodStart").asText()).isEqualTo("2026-06-01");
        assertThat(payload.get("periodEnd").asText()).isEqualTo("2026-06-30");
        assertThat(payload.get("dueDate").asText()).isEqualTo("2026-07-27");
        // JPY 是零小数币种：序列化成整数日元 "1500"/"1000"，不再是 "1500.00"。
        assertThat(payload.get("totalAmount").asText()).isEqualTo("1500");
        assertThat(payload.get("currency").asText()).isEqualTo("JPY");
        assertThat(payload.get("minimumPaymentAmount").asText()).isEqualTo("1000");
        assertThat(payload.get("transactionCount").asInt()).isEqualTo(2);
        assertThat(payload.get("closedAt").asText()).isEqualTo(NOW.toString());
    }

    private Money money(String amount) {
        return new Money(new BigDecimal(amount), Currency.getInstance("JPY"));
    }
}
