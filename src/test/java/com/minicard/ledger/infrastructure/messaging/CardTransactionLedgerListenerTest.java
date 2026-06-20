package com.minicard.ledger.infrastructure.messaging;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.minicard.ledger.application.RecordLedgerEntryCommand;
import com.minicard.ledger.application.RecordLedgerEntryService;
import com.minicard.ledger.domain.LedgerEntryType;
import com.minicard.messaging.event.IntegrationEvent;
import com.minicard.messaging.kafka.IntegrationEventReader;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class CardTransactionLedgerListenerTest {

    private static final Instant NOW = Instant.parse("2026-07-01T00:00:00Z");

    private final ObjectMapper objectMapper =
            new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void postedEventRecordsPurchaseLedgerEntry() throws Exception {
        RecordLedgerEntryService service = mock(RecordLedgerEntryService.class);
        CardTransactionLedgerListener listener = listener(service);
        UUID cardTransactionId = UUID.randomUUID();
        UUID creditAccountId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        listener.onCardTransactionEvent(record(
                eventId,
                "card_transaction.posted",
                payload(cardTransactionId, creditAccountId, "postedAt")
        ));

        ArgumentCaptor<RecordLedgerEntryCommand> command =
                ArgumentCaptor.forClass(RecordLedgerEntryCommand.class);
        verify(service).record(command.capture());
        assertThat(command.getValue().sourceEventId()).isEqualTo(eventId);
        assertThat(command.getValue().entryType()).isEqualTo(LedgerEntryType.CARD_TRANSACTION_POSTED);
        assertThat(command.getValue().sourceId()).isEqualTo(cardTransactionId);
        assertThat(command.getValue().creditAccountId()).isEqualTo(creditAccountId);
        assertThat(command.getValue().amount()).isEqualByComparingTo("100.00");
    }

    @Test
    void irrelevantCardTransactionEventIsSkipped() throws Exception {
        RecordLedgerEntryService service = mock(RecordLedgerEntryService.class);
        CardTransactionLedgerListener listener = listener(service);

        listener.onCardTransactionEvent(record(
                UUID.randomUUID(),
                "card_transaction.refunded",
                payload(UUID.randomUUID(), UUID.randomUUID(), "refundedAt")
        ));

        verifyNoInteractions(service);
    }

    private CardTransactionLedgerListener listener(RecordLedgerEntryService service) {
        return new CardTransactionLedgerListener(
                new IntegrationEventReader(objectMapper),
                service
        );
    }

    private ObjectNode payload(
            UUID cardTransactionId,
            UUID creditAccountId,
            String timeField
    ) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("cardTransactionId", cardTransactionId.toString());
        payload.put("networkTransactionId", "network-123");
        payload.put("authorizationId", UUID.randomUUID().toString());
        payload.put("cardId", "card-123");
        payload.put("creditAccountId", creditAccountId.toString());
        payload.put("amount", "100.00");
        payload.put("currency", "JPY");
        payload.put(timeField, NOW.toString());
        return payload;
    }

    private ConsumerRecord<String, String> record(
            UUID eventId,
            String eventType,
            ObjectNode payload
    ) throws Exception {
        IntegrationEvent event = new IntegrationEvent(
                eventId,
                eventType,
                1,
                NOW,
                payload
        );
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "mini-card.transaction-events.v1",
                0,
                0,
                payload.get("cardTransactionId").asText(),
                objectMapper.writeValueAsString(event)
        );
        record.headers().add(new RecordHeader(
                "eventId",
                eventId.toString().getBytes(StandardCharsets.UTF_8)
        ));
        record.headers().add(new RecordHeader(
                "eventType",
                eventType.getBytes(StandardCharsets.UTF_8)
        ));
        return record;
    }
}
