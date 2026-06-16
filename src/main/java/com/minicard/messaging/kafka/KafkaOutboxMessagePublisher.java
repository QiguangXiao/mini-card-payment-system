package com.minicard.messaging.kafka;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import com.minicard.messaging.event.AuthorizationDecidedEvent;
import com.minicard.messaging.event.AuthorizationExpiredEvent;
import com.minicard.messaging.outbox.application.OutboxMessagePublisher;
import com.minicard.messaging.outbox.domain.OutboxEvent;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Outbox application port 的 Kafka adapter。
 *
 * <p>这个类知道 topic、Kafka headers 和 broker acknowledgement；
 * Outbox domain 不依赖 Kafka infrastructure。</p>
 */
@Component
public class KafkaOutboxMessagePublisher implements OutboxMessagePublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final KafkaTopicsProperties topics;

    public KafkaOutboxMessagePublisher(
            KafkaTemplate<String, String> kafkaTemplate,
            KafkaTopicsProperties topics
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.topics = topics;
    }

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
        if (AuthorizationDecidedEvent.EVENT_TYPE.equals(eventType)) {
            return topics.authorizationEvents();
        }
        if (AuthorizationExpiredEvent.EVENT_TYPE.equals(eventType)) {
            return topics.authorizationLifecycleEvents();
        }
        throw new IllegalArgumentException("no Kafka topic configured for event type " + eventType);
    }

    private void addHeader(ProducerRecord<String, String> record, String name, String value) {
        record.headers().add(name, value.getBytes(StandardCharsets.UTF_8));
    }
}
