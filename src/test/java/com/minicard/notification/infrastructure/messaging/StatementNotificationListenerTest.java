package com.minicard.notification.infrastructure.messaging;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.minicard.messaging.event.IntegrationEvent;
import com.minicard.messaging.kafka.IntegrationEventReader;
import com.minicard.notification.application.RequestNotificationCommand;
import com.minicard.notification.application.RequestNotificationService;
import com.minicard.notification.domain.NotificationSubjectType;
import com.minicard.notification.domain.NotificationType;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class StatementNotificationListenerTest {

    private static final Instant NOW = Instant.parse("2026-07-01T00:00:00Z");

    private final ObjectMapper objectMapper =
            new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void closedEventRequestsStatementNotification() throws Exception {
        RequestNotificationService service = mock(RequestNotificationService.class);
        StatementNotificationListener listener = listener(service);
        UUID statementId = UUID.randomUUID();
        UUID creditAccountId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        listener.onStatementEvent(record(
                eventId,
                "statement.closed",
                payload(statementId, creditAccountId, "closedAt")
        ));

        ArgumentCaptor<RequestNotificationCommand> command =
                ArgumentCaptor.forClass(RequestNotificationCommand.class);
        verify(service).request(command.capture());
        assertThat(command.getValue().sourceEventId()).isEqualTo(eventId);
        assertThat(command.getValue().subjectType()).isEqualTo(NotificationSubjectType.STATEMENT);
        assertThat(command.getValue().subjectId()).isEqualTo(statementId.toString());
        assertThat(command.getValue().recipientKey()).isEqualTo(creditAccountId.toString());
        assertThat(command.getValue().type()).isEqualTo(NotificationType.STATEMENT_CLOSED);
    }

    @Test
    void irrelevantStatementEventIsSkipped() throws Exception {
        RequestNotificationService service = mock(RequestNotificationService.class);
        StatementNotificationListener listener = listener(service);

        listener.onStatementEvent(record(
                UUID.randomUUID(),
                "statement.overdue",
                payload(UUID.randomUUID(), UUID.randomUUID(), "overdueAt")
        ));

        verifyNoInteractions(service);
    }

    private StatementNotificationListener listener(RequestNotificationService service) {
        return new StatementNotificationListener(
                new IntegrationEventReader(objectMapper),
                service
        );
    }

    private ObjectNode payload(
            UUID statementId,
            UUID creditAccountId,
            String timeField
    ) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("statementId", statementId.toString());
        payload.put("creditAccountId", creditAccountId.toString());
        payload.put("periodStart", "2026-06-01");
        payload.put("periodEnd", "2026-06-30");
        payload.put("dueDate", "2026-07-27");
        payload.put("totalAmount", "1500");
        payload.put("currency", "JPY");
        payload.put("minimumPaymentAmount", "1000");
        payload.put("transactionCount", 2);
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
                "mini-card.statement-events.v1",
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
