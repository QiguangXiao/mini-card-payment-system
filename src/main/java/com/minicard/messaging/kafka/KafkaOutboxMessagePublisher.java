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
 * Kafka adapter for the Outbox application port.
 *
 * <p>This class knows about topics, Kafka headers, and broker acknowledgements;
 * the Outbox domain remains independent from Kafka infrastructure.</p>
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
        // Headers allow routing, observability, and schema-version checks
        // without deserializing the JSON payload first.
        addHeader(record, "eventId", event.id().toString());
        addHeader(record, "eventType", event.eventType());
        addHeader(record, "eventVersion", Integer.toString(event.eventVersion()));
        addHeader(record, "aggregateType", event.aggregateType());
        addHeader(record, "aggregateId", event.aggregateId());

        try {
            // Waiting for Kafka acknowledgement lets the Outbox row be marked
            // PUBLISHED only after the broker accepts the event.
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
