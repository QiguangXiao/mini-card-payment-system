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

class RepaymentLedgerListenerTest {

    private static final Instant NOW = Instant.parse("2026-07-10T00:00:00Z");

    private final ObjectMapper objectMapper =
            new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void receivedEventRecordsRepaymentLedgerEntry() throws Exception {
        RecordLedgerEntryService service = mock(RecordLedgerEntryService.class);
        RepaymentLedgerListener listener = listener(service);
        UUID repaymentId = UUID.randomUUID();
        UUID statementId = UUID.randomUUID();
        UUID creditAccountId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        listener.onRepaymentEvent(record(
                eventId,
                "repayment.received",
                payload(repaymentId, statementId, creditAccountId, "receivedAt")
        ));

        ArgumentCaptor<RecordLedgerEntryCommand> command =
                ArgumentCaptor.forClass(RecordLedgerEntryCommand.class);
        verify(service).record(command.capture());
        assertThat(command.getValue().sourceEventId()).isEqualTo(eventId);
        assertThat(command.getValue().entryType()).isEqualTo(LedgerEntryType.REPAYMENT_RECEIVED);
        assertThat(command.getValue().sourceId()).isEqualTo(repaymentId);
        assertThat(command.getValue().creditAccountId()).isEqualTo(creditAccountId);
        assertThat(command.getValue().amount()).isEqualByComparingTo("500.00");
    }

    @Test
    void irrelevantRepaymentEventIsSkipped() throws Exception {
        RecordLedgerEntryService service = mock(RecordLedgerEntryService.class);
        RepaymentLedgerListener listener = listener(service);

        listener.onRepaymentEvent(record(
                UUID.randomUUID(),
                "repayment.failed",
                payload(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "failedAt")
        ));

        verifyNoInteractions(service);
    }

    private RepaymentLedgerListener listener(RecordLedgerEntryService service) {
        return new RepaymentLedgerListener(
                new IntegrationEventReader(objectMapper),
                service
        );
    }

    private ObjectNode payload(
            UUID repaymentId,
            UUID statementId,
            UUID creditAccountId,
            String timeField
    ) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("repaymentId", repaymentId.toString());
        payload.put("statementId", statementId.toString());
        payload.put("creditAccountId", creditAccountId.toString());
        payload.put("amount", "500.00");
        payload.put("currency", "JPY");
        payload.put("statementPaidAmount", "500.00");
        payload.put("statementRemainingAmount", "1000.00");
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
                "mini-card.repayment-events.v1",
                0,
                0,
                payload.get("creditAccountId").asText(),
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
