package com.minicard.messaging.kafka;

import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minicard.messaging.event.IntegrationEventEnvelope;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.stereotype.Component;

/**
 * Kafka inbound adapter 共用的 integration event reader。
 *
 * <p>它只处理 transport contract：JSON envelope、header、一致的 eventType/version 校验。
 * 具体 consumer 自己决定关心哪些 payload type，避免一个 bounded context 依赖另一个
 * bounded context 的 infrastructure reader。</p>
 */
@Component
public class IntegrationEventReader {

    private final ObjectMapper objectMapper;

    public IntegrationEventReader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String eventType(ConsumerRecord<String, String> record) {
        try {
            // 先轻量读取 eventType，让 consumer 可以只处理自己订阅的消息类型。
            // 合法但不感兴趣的事件不应该被当成坏消息送进 DLT。
            JsonNode root = objectMapper.readTree(record.value());
            return requiredText(root, "eventType");
        } catch (JsonProcessingException exception) {
            throw new EventContractException("integration event JSON is invalid", exception);
        }
    }

    public <T> IntegrationEventEnvelope<T> read(
            ConsumerRecord<String, String> record,
            Class<T> payloadType,
            String expectedEventType,
            int expectedEventVersion
    ) {
        try {
            requireEventType(record, expectedEventType);
            JavaType envelopeType = objectMapper.getTypeFactory().constructParametricType(
                    IntegrationEventEnvelope.class,
                    payloadType
            );
            IntegrationEventEnvelope<T> event = objectMapper.readValue(record.value(), envelopeType);
            validate(record, event, expectedEventType, expectedEventVersion);
            return event;
        } catch (JsonProcessingException exception) {
            throw new EventContractException(expectedEventType + " JSON is invalid", exception);
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
                    "unsupported event version " + event.eventVersion()
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
