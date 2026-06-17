package com.minicard.authorization.infrastructure.messaging;

import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minicard.authorization.infrastructure.messaging.payload.AuthorizationApprovedPayload;
import com.minicard.authorization.infrastructure.messaging.payload.AuthorizationDeclinedPayload;
import com.minicard.messaging.event.IntegrationEventEnvelope;
import com.minicard.messaging.kafka.EventContractException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.stereotype.Component;

/**
 * Authorization message contract parser。
 *
 * <p>Parser 属于 Authorization 的 messaging adapter，而不是通用 Kafka client。
 * Kafka infrastructure 只负责运输字符串；这里负责 Authorization payload schema validation。</p>
 */
@Component
public class AuthorizationEventParser {

    private final ObjectMapper objectMapper;
    private final JavaType approvedEventType;
    private final JavaType declinedEventType;

    public AuthorizationEventParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.approvedEventType = objectMapper.getTypeFactory().constructParametricType(
                IntegrationEventEnvelope.class,
                AuthorizationApprovedPayload.class
        );
        this.declinedEventType = objectMapper.getTypeFactory().constructParametricType(
                IntegrationEventEnvelope.class,
                AuthorizationDeclinedPayload.class
        );
    }

    public AuthorizationDecisionMessage parseDecisionEvent(
            ConsumerRecord<String, String> record
    ) {
        try {
            JsonNode root = objectMapper.readTree(record.value());
            String eventType = requiredText(root, "eventType");
            if (AuthorizationApprovedPayload.EVENT_TYPE.equals(eventType)) {
                IntegrationEventEnvelope<AuthorizationApprovedPayload> event =
                        objectMapper.readValue(record.value(), approvedEventType);
                validate(record, event, AuthorizationApprovedPayload.EVENT_TYPE,
                        AuthorizationApprovedPayload.EVENT_VERSION);
                return AuthorizationDecisionMessage.approved(event.eventId(), event.payload());
            }
            if (AuthorizationDeclinedPayload.EVENT_TYPE.equals(eventType)) {
                IntegrationEventEnvelope<AuthorizationDeclinedPayload> event =
                        objectMapper.readValue(record.value(), declinedEventType);
                validate(record, event, AuthorizationDeclinedPayload.EVENT_TYPE,
                        AuthorizationDeclinedPayload.EVENT_VERSION);
                return AuthorizationDecisionMessage.declined(event.eventId(), event.payload());
            }
            throw new EventContractException("unsupported authorization decision event type " + eventType);
        } catch (JsonProcessingException exception) {
            throw new EventContractException("authorization event JSON is invalid", exception);
        }
    }

    private void validate(
            ConsumerRecord<String, String> record,
            IntegrationEventEnvelope<?> event,
            String expectedEventType,
            int expectedEventVersion
    ) {
        if (!expectedEventType.equals(event.eventType())) {
            throw new EventContractException("unsupported event type " + event.eventType());
        }
        if (event.eventVersion() != expectedEventVersion) {
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

        Header eventTypeHeader = record.headers().lastHeader("eventType");
        if (eventTypeHeader != null
                && !event.eventType().equals(
                        new String(eventTypeHeader.value(), StandardCharsets.UTF_8)
                )) {
            throw new EventContractException("eventType header does not match payload");
        }
    }

    private String requiredText(JsonNode root, String fieldName) {
        JsonNode value = root.get(fieldName);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new EventContractException(fieldName + " is required");
        }
        return value.asText();
    }
}
