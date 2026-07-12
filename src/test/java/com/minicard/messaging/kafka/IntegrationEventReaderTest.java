package com.minicard.messaging.kafka;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.minicard.messaging.event.IntegrationEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IntegrationEventReaderTest {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final IntegrationEventReader reader = new IntegrationEventReader(objectMapper);

    @Test
    // 测试目的：验证 reader 能解析 self-describing body envelope，并读取 payload 字段。
    // variant：headers 与 body 一致时正常读取，但 dispatch 仍以后续 body eventType 为准。
    void readsJsonPayloadWithMatchingHeaders() throws Exception {
        IntegrationEvent event = event("authorization.approved", 1);
        ConsumerRecord<String, String> record = record(
                objectMapper.writeValueAsString(event),
                event.eventId(),
                event.eventType()
        );

        IntegrationEvent parsed = reader.read(record);

        assertThat(parsed.eventId()).isEqualTo(event.eventId());
        assertThat(parsed.eventType()).isEqualTo("authorization.approved");
        assertThat(reader.requiredText(parsed.payload(), "authorizationId"))
                .isEqualTo(event.payload().get("authorizationId").asText());
        assertThat(reader.requiredInstant(parsed.payload(), "approvedAt"))
                .isEqualTo(Instant.parse("2026-06-14T00:00:00Z"));
        assertThat(reader.requiredUuid(parsed.payload(), "authorizationId"))
                .isEqualTo(UUID.fromString(event.payload().get("authorizationId").asText()));
        assertThat(reader.requiredDecimal(parsed.payload(), "amount"))
                .isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(reader.requiredCurrency(parsed.payload(), "currency"))
                .isEqualTo(Currency.getInstance("JPY"));
    }

    @Test
    // 测试目的：验证缺少 payload 属于 event contract failure。
    // variant：body envelope 没有 payload，listener 应抛 EventContractException，由 Kafka error handler 投 DLT。
    void rejectsMissingPayloadAsContractFailure() throws Exception {
        IntegrationEvent event = new IntegrationEvent(
                UUID.randomUUID(),
                "authorization.approved",
                1,
                Instant.parse("2026-06-14T00:00:00Z"),
                null
        );
        ConsumerRecord<String, String> record = record(
                objectMapper.writeValueAsString(event),
                event.eventId(),
                event.eventType()
        );

        assertThatThrownBy(() -> reader.read(record))
                .isInstanceOf(EventContractException.class)
                .hasMessage("eventId and payload are required");
    }

    @Test
    // 测试目的：验证 header 只是 observability hint，不是消费必需条件。
    // variant：console-producer/手工 replay 无 header，body envelope 完整时仍可消费。
    void readsPayloadWithoutAnyHeaders() throws Exception {
        // envelope 是 self-describing 的：console-producer 手工 replay 的消息没有 header，
        // 也必须能被正常消费。如果 read() 强依赖 header，self-describing envelope 就名存实亡。
        IntegrationEvent event = event("authorization.approved", 1);
        ConsumerRecord<String, String> record =
                recordWithoutHeaders(objectMapper.writeValueAsString(event));

        IntegrationEvent parsed = reader.read(record);

        assertThat(parsed.eventId()).isEqualTo(event.eventId());
        assertThat(parsed.eventType()).isEqualTo("authorization.approved");
    }

    @Test
    // 测试目的：验证 payload 字段类型错误会变成 contract failure。
    // variant：approvedAt 不是 ISO-8601 instant，避免 consumer 用错误时间继续执行业务。
    void rejectsInvalidInstantPayloadFieldAsContractFailure() {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("approvedAt", "not-an-instant");

        assertThatThrownBy(() -> reader.requiredInstant(payload, "approvedAt"))
                .isInstanceOf(EventContractException.class)
                .hasMessage("approvedAt must be an ISO-8601 instant");
    }

    @Test
    // 测试目的：UUID/金额/币种的确定性格式错误必须直接归类为 contract failure，不能浪费 Kafka retry。
    void rejectsInvalidTypedPayloadFieldsAsContractFailures() {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("authorizationId", "not-a-uuid");
        payload.put("amount", "not-a-decimal");
        payload.put("currency", "NOT-A-CURRENCY");

        assertThatThrownBy(() -> reader.requiredUuid(payload, "authorizationId"))
                .isInstanceOf(EventContractException.class)
                .hasMessage("authorizationId must be a valid UUID");
        assertThatThrownBy(() -> reader.requiredDecimal(payload, "amount"))
                .isInstanceOf(EventContractException.class)
                .hasMessage("amount must be a valid decimal");
        assertThatThrownBy(() -> reader.requiredCurrency(payload, "currency"))
                .isInstanceOf(EventContractException.class)
                .hasMessage("currency must be a valid ISO 4217 currency");
    }

    private IntegrationEvent event(String eventType, int version) {
        Instant now = Instant.parse("2026-06-14T00:00:00Z");
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("authorizationId", UUID.randomUUID().toString());
        payload.put("cardId", "card-123");
        payload.put("amount", "100.00");
        payload.put("currency", "JPY");
        payload.put("approvedAt", now.toString());
        return new IntegrationEvent(
                UUID.randomUUID(),
                eventType,
                version,
                now,
                payload
        );
    }

    private ConsumerRecord<String, String> record(
            String value,
            UUID eventId,
            String eventType
    ) {
        ConsumerRecord<String, String> record = recordWithoutHeaders(value);
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

    private ConsumerRecord<String, String> recordWithoutHeaders(String value) {
        return new ConsumerRecord<>(
                "mini-card.authorization-events.v1",
                0,
                0,
                "authorization-id",
                value
        );
    }
}
