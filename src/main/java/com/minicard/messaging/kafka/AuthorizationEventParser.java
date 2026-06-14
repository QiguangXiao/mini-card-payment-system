package com.minicard.messaging.kafka;

import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minicard.messaging.event.AuthorizationDecidedEvent;
import com.minicard.messaging.event.IntegrationEventEnvelope;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.stereotype.Component;

/**
 * Converts the external Kafka contract into the typed event used by consumers.
 *
 * <p>Contract validation is centralized here so every consumer rejects malformed
 * or unsupported events consistently. These errors are permanent, so the Kafka
 * error handler sends them directly to the consumer-specific DLT.</p>
 */
@Component
public class AuthorizationEventParser {

    private final ObjectMapper objectMapper;
    private final JavaType eventType;

    public AuthorizationEventParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.eventType = objectMapper.getTypeFactory().constructParametricType(
                IntegrationEventEnvelope.class,
                AuthorizationDecidedEvent.class
        );
    }

    public IntegrationEventEnvelope<AuthorizationDecidedEvent> parse(
            ConsumerRecord<String, String> record
    ) {
        try {
            IntegrationEventEnvelope<AuthorizationDecidedEvent> event =
                    objectMapper.readValue(record.value(), eventType);
            validate(record, event);
            return event;
        } catch (JsonProcessingException exception) {
            throw new EventContractException("authorization event JSON is invalid", exception);
        }
    }

    private void validate(
            ConsumerRecord<String, String> record,
            IntegrationEventEnvelope<AuthorizationDecidedEvent> event
    ) {
        if (!AuthorizationDecidedEvent.EVENT_TYPE.equals(event.eventType())) {
            throw new EventContractException("unsupported event type " + event.eventType());
        }
        if (event.eventVersion() != AuthorizationDecidedEvent.EVENT_VERSION) {
            throw new EventContractException(
                    "unsupported authorization event version " + event.eventVersion()
            );
        }
        if (event.eventId() == null || event.payload() == null) {
            throw new EventContractException("eventId and payload are required");
        }

        Header eventIdHeader = record.headers().lastHeader("eventId");
        if (eventIdHeader == null
                || !event.eventId().toString().equals(
                        new String(eventIdHeader.value(), StandardCharsets.UTF_8)
                )) {
            throw new EventContractException("eventId header does not match payload");
        }
    }
}
