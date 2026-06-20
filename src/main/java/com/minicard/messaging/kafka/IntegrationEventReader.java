package com.minicard.messaging.kafka;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;

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
 * <p>关键词：消息读取, 契约校验, Kafka header, integration event reader,
 * contract validation, JSON envelope, メッセージ読取(メッセージよみとり),
 * 契約検証(けいやくけんしょう)。</p>
 *
 * <p>它只处理 transport contract：JSON envelope、header 和基础字段校验。
 * 具体 consumer 自己根据 eventType 读取 JsonNode payload，避免为每种消息创建 payload class。</p>
 */
@Component
@RequiredArgsConstructor
public class IntegrationEventReader {

    /** Jackson ObjectMapper 负责 JSON envelope 反序列化。 */
    private final ObjectMapper objectMapper;

    /**
     * 读取 Kafka record 并校验 transport contract。
     */
    public IntegrationEvent read(ConsumerRecord<String, String> record) {
        try {
            IntegrationEvent event = objectMapper.readValue(record.value(), IntegrationEvent.class);
            validate(record, event);
            return event;
        } catch (JsonProcessingException exception) {
            // JSON 格式错误是永久 contract failure，重试同一消息也不会成功。
            throw new EventContractException("integration event JSON is invalid", exception);
        }
    }

    /**
     * 校验 envelope 字段和 header 一致性。
     */
    private void validate(
            ConsumerRecord<String, String> record,
            IntegrationEvent event
    ) {
        if (event.eventId() == null || event.payload() == null || event.payload().isNull()) {
            // 缺 eventId/payload 时无法做 idempotency 和业务解析，必须拒绝。
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
            // header/payload 不一致说明消息契约损坏，不能进入业务 consumer。
            throw new EventContractException("eventId header does not match payload");
        }

        Header eventTypeHeader = record.headers().lastHeader("eventType");
        if (eventTypeHeader != null
                && !event.eventType().equals(
                        new String(eventTypeHeader.value(), StandardCharsets.UTF_8)
                )) {
            // eventType header 用于 consumer 快速过滤，必须和 payload 保持一致。
            throw new EventContractException("eventType header does not match payload");
        }
    }

    /**
     * 从 payload 中读取必填字符串字段。
     *
     * <p>这是 consumer 共享的小工具，避免每个消费者重复处理 null/blank contract check。</p>
     */
    public String requiredText(JsonNode root, String fieldName) {
        JsonNode value = root.get(fieldName);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new EventContractException(fieldName + " is required");
        }
        return value.asText();
    }

    /**
     * 从 payload 中读取必填 ISO-8601 instant 字段。
     *
     * <p>时间字段属于 event contract。格式坏了不应表现成低层 DateTimeParseException，
     * 而应进入 Kafka error handler 的 contract failure 路径。</p>
     */
    public Instant requiredInstant(JsonNode root, String fieldName) {
        String value = requiredText(root, fieldName);
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            throw new EventContractException(fieldName + " must be an ISO-8601 instant", exception);
        }
    }
}
