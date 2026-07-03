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

class CardTransactionNotificationListenerTest {

    private static final Instant NOW = Instant.parse("2026-06-20T00:00:00Z");

    private final ObjectMapper objectMapper =
            new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void postedEventRequestsPostedNotification() throws Exception {
        RequestNotificationService service = mock(RequestNotificationService.class);
        CardTransactionNotificationListener listener = listener(service);
        UUID cardTransactionId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        listener.onCardTransactionEvent(record(
                eventId,
                "card_transaction.posted",
                payload(cardTransactionId, "card-123", "postedAt")
        ));

        ArgumentCaptor<RequestNotificationCommand> command =
                ArgumentCaptor.forClass(RequestNotificationCommand.class);
        verify(service).requestNotification(command.capture());
        assertThat(command.getValue().sourceEventId()).isEqualTo(eventId);
        assertThat(command.getValue().subjectType()).isEqualTo(NotificationSubjectType.CARD_TRANSACTION);
        assertThat(command.getValue().subjectId()).isEqualTo(cardTransactionId.toString());
        assertThat(command.getValue().recipientKey()).isEqualTo("card-123");
        assertThat(command.getValue().type()).isEqualTo(NotificationType.CARD_TRANSACTION_POSTED);
    }

    @Test
    void irrelevantCardTransactionEventIsSkipped() throws Exception {
        RequestNotificationService service = mock(RequestNotificationService.class);
        CardTransactionNotificationListener listener = listener(service);

        listener.onCardTransactionEvent(record(
                UUID.randomUUID(),
                "card_transaction.refunded",
                payload(UUID.randomUUID(), "card-123", "refundedAt")
        ));

        verifyNoInteractions(service);
    }

    private CardTransactionNotificationListener listener(
            RequestNotificationService service
    ) {
        return new CardTransactionNotificationListener(
                new IntegrationEventReader(objectMapper),
                service
        );
    }

    private ObjectNode payload(UUID cardTransactionId, String cardId, String timeField) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("cardTransactionId", cardTransactionId.toString());
        payload.put("authorizationId", UUID.randomUUID().toString());
        payload.put("cardId", cardId);
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
