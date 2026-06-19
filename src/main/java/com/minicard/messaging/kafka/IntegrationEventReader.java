package com.minicard.messaging.kafka;

import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minicard.messaging.event.IntegrationEvent;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.stereotype.Component;

/**
 * Kafka inbound adapter 共用的 integration event reader。
 *
 * <p>它只处理 transport contract：JSON envelope、header 和基础字段校验。
 * 具体 consumer 自己根据 eventType 读取 JsonNode payload，避免为每种消息创建 payload class。</p>
 */
@Component
@RequiredArgsConstructor
public class IntegrationEventReader {

    private final ObjectMapper objectMapper;

    public IntegrationEvent read(ConsumerRecord<String, String> record) {
        try {
            IntegrationEvent event = objectMapper.readValue(record.value(), IntegrationEvent.class);
            validate(record, event);
            return event;
        } catch (JsonProcessingException exception) {
            throw new EventContractException("integration event JSON is invalid", exception);
        }
    }

    private void validate(
            ConsumerRecord<String, String> record,
            IntegrationEvent event
    ) {
        if (event.eventId() == null || event.payload() == null || event.payload().isNull()) {
            throw new EventContractException("eventId and payload are required");
        }
        if (event.eventType() == null || event.eventType().isBlank()) {
            throw new EventContractException("eventType is required");
        }
        if (event.eventVersion() < 1) {
            throw new EventContractException("eventVersion must be positive");
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

    public String requiredText(JsonNode root, String fieldName) {
        JsonNode value = root.get(fieldName);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new EventContractException(fieldName + " is required");
        }
        return value.asText();
    }
}
