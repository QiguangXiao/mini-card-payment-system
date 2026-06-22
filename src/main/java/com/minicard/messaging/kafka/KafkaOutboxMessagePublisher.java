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
 * <p>关键词：Kafka 发布, Outbox adapter, broker ack, Kafka publication,
 * acknowledgement, at-least-once, Kafka発行(Kafkaはっこう),
 * 確認応答(かくにんおうとう)。</p>
 *
 * <p>这个类知道 topic、Kafka headers 和 broker acknowledgement；
 * Outbox 自身不依赖 Kafka infrastructure。</p>
 */
@Component
@RequiredArgsConstructor
public class KafkaOutboxMessagePublisher implements OutboxMessagePublisher {

    /** Spring Kafka 发送模板；这里只作为 infrastructure dependency，不进入 domain。 */
    private final KafkaTemplate<String, String> kafkaTemplate;
    /** topic 名由配置提供，避免业务代码写死环境差异。 */
    private final KafkaTopicsProperties topics;

    /**
     * 发布 OutboxEvent 并等待 Kafka broker ack。
     */
    @Override
    public void publish(OutboxEvent event, Duration timeout) {
        // ProducerRecord 允许显式设置 topic、key、payload 和 headers。
        // 如果只 send(topic, payload)，下游就拿不到 eventId/eventType/version 这类 contract metadata。
        // ProducerRecord 包含 topic、partition key 和 payload；partition key 影响同一聚合事件顺序。
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
            // KafkaTemplate.send 返回 CompletableFuture；这里等待 broker ack 后才让 Outbox 标记 PUBLISHED。
            // 如果 fire-and-forget，broker 实际失败时 Outbox 仍可能误以为消息已发出。
            // 等待 Kafka acknowledgement 后才把 Outbox row 标记为 PUBLISHED。
            // 这里仍不是分布式事务，所以整体语义仍是 at-least-once。
            kafkaTemplate.send(record).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            // Java 并发约定：捕获 InterruptedException 后恢复 interrupt flag，避免吞掉 shutdown 信号。
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Kafka publication was interrupted", exception);
        } catch (Exception exception) {
            // 其他 Kafka/timeout 错误交给 OutboxWorker 统一转 retry/DEAD。
            throw new IllegalStateException("Kafka publication failed", exception);
        }
    }

    /**
     * 根据 eventType 前缀选择 topic。
     */
    private String topicFor(String eventType) {
        // Outbox 是通用 reliable delivery 机制；topic routing 属于 Kafka adapter。
        // 这里按业务事实所属上下文拆 topic，避免把 card_transaction 事件塞进 authorization topic。
        if (eventType.startsWith("authorization.")) {
            return topics.authorizationEvents();
        }
        if (eventType.startsWith("card_transaction.")) {
            return topics.transactionEvents();
        }
        if (eventType.startsWith("statement.")) {
            return topics.statementEvents();
        }
        if (eventType.startsWith("repayment.")) {
            return topics.repaymentEvents();
        }
        // 未知事件类型属于开发错误，不能静默发布到错误 topic。
        throw new IllegalArgumentException("unsupported integration event type " + eventType);
    }

    /**
     * 写 Kafka header，统一使用 UTF-8 字节。
     */
    private void addHeader(ProducerRecord<String, String> record, String name, String value) {
        record.headers().add(name, value.getBytes(StandardCharsets.UTF_8));
    }
}
