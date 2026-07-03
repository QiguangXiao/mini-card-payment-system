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

class RepaymentNotificationListenerTest {

    private static final Instant NOW = Instant.parse("2026-07-10T00:00:00Z");

    private final ObjectMapper objectMapper =
            new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void receivedEventRequestsRepaymentNotification() throws Exception {
        RequestNotificationService service = mock(RequestNotificationService.class);
        RepaymentNotificationListener listener = listener(service);
        UUID repaymentId = UUID.randomUUID();
        UUID statementId = UUID.randomUUID();
        UUID creditAccountId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        listener.onRepaymentEvent(record(
                eventId,
                "repayment.received",
                payload(repaymentId, statementId, creditAccountId, "receivedAt")
        ));

        ArgumentCaptor<RequestNotificationCommand> command =
                ArgumentCaptor.forClass(RequestNotificationCommand.class);
        verify(service).requestNotification(command.capture());
        assertThat(command.getValue().sourceEventId()).isEqualTo(eventId);
        assertThat(command.getValue().subjectType()).isEqualTo(NotificationSubjectType.REPAYMENT);
        assertThat(command.getValue().subjectId()).isEqualTo(repaymentId.toString());
        assertThat(command.getValue().recipientKey()).isEqualTo(creditAccountId.toString());
        assertThat(command.getValue().type()).isEqualTo(NotificationType.REPAYMENT_RECEIVED);
    }

    @Test
    void irrelevantRepaymentEventIsSkipped() throws Exception {
        RequestNotificationService service = mock(RequestNotificationService.class);
        RepaymentNotificationListener listener = listener(service);

        listener.onRepaymentEvent(record(
                UUID.randomUUID(),
                "repayment.failed",
                payload(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "failedAt")
        ));

        verifyNoInteractions(service);
    }

    private RepaymentNotificationListener listener(RequestNotificationService service) {
        return new RepaymentNotificationListener(
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
