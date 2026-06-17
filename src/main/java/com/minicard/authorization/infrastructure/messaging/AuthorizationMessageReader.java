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
 * Authorization message reader。
 *
 * <p>Reader 负责 transport contract parsing/validation：JSON、eventId header、
 * eventType header、eventVersion。Listener 负责决定自己关心哪些明确事件类型。</p>
 */
@Component
public class AuthorizationMessageReader {

    private final ObjectMapper objectMapper;
    private final JavaType approvedEventType;
    private final JavaType declinedEventType;

    public AuthorizationMessageReader(ObjectMapper objectMapper) {
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

    public String eventType(ConsumerRecord<String, String> record) {
        try {
            // 只读取 envelope 的 eventType，让 consumer 能先判断是否感兴趣；
            // 不感兴趣的合法事件不应该被当成坏消息送进 DLT。
            JsonNode root = objectMapper.readTree(record.value());
            return requiredText(root, "eventType");
        } catch (JsonProcessingException exception) {
            throw new EventContractException("authorization event JSON is invalid", exception);
        }
    }

    public IntegrationEventEnvelope<AuthorizationApprovedPayload> readApproved(
            ConsumerRecord<String, String> record
    ) {
        try {
            // 先校验 eventType 再按具体 payload 反序列化。
            // 否则把 expired payload 当 approved 读时，会得到低层 Jackson 字段错误，语义不够清楚。
            requireEventType(record, AuthorizationApprovedPayload.EVENT_TYPE);
            IntegrationEventEnvelope<AuthorizationApprovedPayload> event =
                    objectMapper.readValue(record.value(), approvedEventType);
            validate(record, event, AuthorizationApprovedPayload.EVENT_TYPE,
                    AuthorizationApprovedPayload.EVENT_VERSION);
            return event;
        } catch (JsonProcessingException exception) {
            throw new EventContractException("authorization approved event JSON is invalid", exception);
        }
    }

    public IntegrationEventEnvelope<AuthorizationDeclinedPayload> readDeclined(
            ConsumerRecord<String, String> record
    ) {
        try {
            // 和 readApproved() 一样，先检查 eventType，再进入 typed payload parsing。
            requireEventType(record, AuthorizationDeclinedPayload.EVENT_TYPE);
            IntegrationEventEnvelope<AuthorizationDeclinedPayload> event =
                    objectMapper.readValue(record.value(), declinedEventType);
            validate(record, event, AuthorizationDeclinedPayload.EVENT_TYPE,
                    AuthorizationDeclinedPayload.EVENT_VERSION);
            return event;
        } catch (JsonProcessingException exception) {
            throw new EventContractException("authorization declined event JSON is invalid", exception);
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

    private void requireEventType(
            ConsumerRecord<String, String> record,
            String expectedEventType
    ) throws JsonProcessingException {
        JsonNode root = objectMapper.readTree(record.value());
        String actualEventType = requiredText(root, "eventType");
        if (!expectedEventType.equals(actualEventType)) {
            throw new EventContractException("unsupported event type " + actualEventType);
        }
    }
}
