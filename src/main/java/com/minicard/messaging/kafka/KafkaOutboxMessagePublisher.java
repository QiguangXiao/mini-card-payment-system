package com.minicard.messaging.kafka;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import com.minicard.messaging.outbox.OutboxEvent;
import com.minicard.messaging.outbox.OutboxMessagePublisher;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Outbox 可靠投递机制的 Kafka adapter。
 *
 * <p>这个类知道 topic、Kafka headers 和 broker acknowledgement；
 * Outbox 自身不依赖 Kafka infrastructure。</p>
 */
@Component
@RequiredArgsConstructor
public class KafkaOutboxMessagePublisher implements OutboxMessagePublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final KafkaTopicsProperties topics;

    @Override
    public void publish(OutboxEvent event, Duration timeout) {
        ProducerRecord<String, String> record = new ProducerRecord<>(
                topicFor(event.eventType()),
                event.partitionKey(),
                event.payload()
        );
        // Headers 支持 routing、observability 和 schema-version check，
        // 消费者无需先反序列化 JSON payload 就能做基础判断。
        addHeader(record, "eventId", event.id().toString());
        addHeader(record, "eventType", event.eventType());
        addHeader(record, "eventVersion", Integer.toString(event.eventVersion()));
        addHeader(record, "aggregateType", event.aggregateType());
        addHeader(record, "aggregateId", event.aggregateId());

        try {
            // 等待 Kafka acknowledgement 后才把 Outbox row 标记为 PUBLISHED。
            // 这里仍不是分布式事务，所以整体语义仍是 at-least-once。
            kafkaTemplate.send(record).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Kafka publication was interrupted", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("Kafka publication failed", exception);
        }
    }

    private String topicFor(String eventType) {
        // 当前学习项目先用一个 Authorization topic 承载多种 eventType。
        // 什么时候拆 topic，应该由吞吐、权限、retention 等真实需求决定。
        return topics.authorizationEvents();
    }

    private void addHeader(ProducerRecord<String, String> record, String name, String value) {
        record.headers().add(name, value.getBytes(StandardCharsets.UTF_8));
    }
}
