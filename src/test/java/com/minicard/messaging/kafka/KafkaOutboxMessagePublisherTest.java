package com.minicard.messaging.kafka;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.minicard.messaging.outbox.OutboxEvent;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KafkaOutboxMessagePublisherTest {

    private static final Instant NOW = Instant.parse("2026-07-01T00:00:00Z");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    // 测试目的：验证 producer 写出的 Kafka headers 与 body envelope 元信息一致。
    // variant：header 不作为 consumer dispatch 真相，但要服务 kcat/DLT/排查，所以必须 mirror envelope。
    void headersMirrorEnvelopeFields() throws Exception {
        // header 是纯 observability 元数据，不参与 consumer correctness(consumer 只读 body)。
        // 但它必须和 envelope 一致：header 说谎不会丢消息，
        // 却会在 kcat/DLT/线上排查时把 on-call 引向错误的事件。
        KafkaTemplate<String, String> kafkaTemplate = kafkaTemplate();
        KafkaOutboxMessagePublisher publisher = new KafkaOutboxMessagePublisher(kafkaTemplate, topics());
        UUID eventId = UUID.randomUUID();
        OutboxEvent event = outboxEvent(eventId, "authorization.approved");

        publisher.publish(event, Duration.ofSeconds(5));

        ProducerRecord<String, String> record = capturedRecord(kafkaTemplate);
        JsonNode envelope = objectMapper.readTree(record.value());
        assertThat(header(record, "eventId")).isEqualTo(envelope.get("eventId").asText());
        assertThat(header(record, "eventType")).isEqualTo(envelope.get("eventType").asText());
        assertThat(header(record, "eventVersion"))
                .isEqualTo(Integer.toString(envelope.get("eventVersion").asInt()));
        assertThat(header(record, "aggregateType")).isEqualTo("Authorization");
        assertThat(header(record, "aggregateId")).isEqualTo(event.aggregateId());
    }

    @Test
    // 测试目的：验证 eventType 前缀路由到对应 topic，并保留 partition key。
    // variant：authorization.* 应发 authorization topic，同一 aggregate 依靠 key 保持 partition 内顺序。
    void routesEventTypePrefixToOwningTopicWithPartitionKey() {
        KafkaTemplate<String, String> kafkaTemplate = kafkaTemplate();
        KafkaOutboxMessagePublisher publisher = new KafkaOutboxMessagePublisher(kafkaTemplate, topics());
        OutboxEvent event = outboxEvent(UUID.randomUUID(), "authorization.approved");

        publisher.publish(event, Duration.ofSeconds(5));

        ProducerRecord<String, String> record = capturedRecord(kafkaTemplate);
        assertThat(record.topic()).isEqualTo("authorization-events");
        // partition key 决定同一聚合事件的分区内顺序，丢了 key 顺序保证就消失。
        assertThat(record.key()).isEqualTo(event.partitionKey());
    }

    @Test
    // 测试目的：验证未知 eventType 是开发/契约错误，不能猜 topic。
    // variant：unsupported prefix 直接抛异常，避免消息发到无人消费的错误 topic。
    void rejectsUnsupportedEventTypeInsteadOfGuessingTopic() {
        KafkaOutboxMessagePublisher publisher =
                new KafkaOutboxMessagePublisher(kafkaTemplate(), topics());
        OutboxEvent event = outboxEvent(UUID.randomUUID(), "unknown.event");

        // 未知事件类型是开发错误：静默发到错误 topic 会让消息"发布成功但没人消费"。
        assertThatThrownBy(() -> publisher.publish(event, Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown.event");
    }

    @SuppressWarnings("unchecked")
    private KafkaTemplate<String, String> kafkaTemplate() {
        KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
        return kafkaTemplate;
    }

    @SuppressWarnings("unchecked")
    private ProducerRecord<String, String> capturedRecord(KafkaTemplate<String, String> kafkaTemplate) {
        ArgumentCaptor<ProducerRecord<String, String>> captor =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());
        return captor.getValue();
    }

    // 按 outbox adapter 的真实做法构造：envelope 与 outbox 列出自同一组值。
    private OutboxEvent outboxEvent(UUID eventId, String eventType) {
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("eventId", eventId.toString());
        envelope.put("eventType", eventType);
        envelope.put("eventVersion", 1);
        envelope.put("occurredAt", NOW.toString());
        envelope.set("payload", objectMapper.createObjectNode().put("cardId", "card-123"));
        return OutboxEvent.pending(
                eventId,
                "Authorization",
                "auth-1",
                eventType,
                1,
                "auth-1",
                envelope.toString(),
                NOW
        );
    }

    private String header(ProducerRecord<String, String> record, String name) {
        return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }

    private KafkaTopicsProperties topics() {
        return new KafkaTopicsProperties(
                "authorization-events",
                "transaction-events",
                "statement-events",
                "repayment-events",
                "notification-dlt",
                "risk-feature-dlt",
                "ledger-dlt"
        );
    }
}
