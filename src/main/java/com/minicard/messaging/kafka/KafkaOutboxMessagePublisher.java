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
 *
 * <p>Kafka record 的四个基本部分：topic 决定消息类别，key 决定 partition，value 是 JSON event envelope，
 * headers 是排障元数据。OutboxWorker 把数据库里的 {@link OutboxEvent} 交给本 adapter，
 * 本类转换成 ProducerRecord 并等待 broker ack；只有 ack 成功，worker 才能把 Outbox row 标记 PUBLISHED。</p>
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
        // 阶段 1：把 Outbox row 转成 Kafka record。ProducerRecord 允许显式设置 topic、key、payload 和 headers。
        // 如果只 send(topic, payload)，下游就拿不到 eventId/eventType/version 这类 contract metadata。
        // ProducerRecord 包含 topic、partition key 和 payload；partition key 影响同一聚合事件顺序。
        ProducerRecord<String, String> record = new ProducerRecord<>(
                topicFor(event.eventType()), // topic：按 integration event 类型选择业务 channel。
                event.partitionKey(),        // key：Kafka 对它做 hash，决定进入哪个 partition。
                event.payload()              // value：完整 JSON envelope，是 consumer correctness 的输入。
        );
        // 阶段 2：补充 headers。它们是纯 observability 元数据：kcat/DLT/日志排查时不解析 JSON 就能看到消息身份。
        // consumer correctness 只依赖 body envelope，不读 header——缺 header 的手工 replay 照常可消费。
        // header 与 envelope 同源仍由测试钉住(见 KafkaOutboxMessagePublisherTest)：
        // header 说谎不影响 correctness，但会在 DLT/线上排查时误导 on-call。
        addHeader(record, "eventId", event.id().toString());                    // 跨日志/DLT 定位这次业务事实。
        addHeader(record, "eventType", event.eventType());                      // 不解析 JSON 也能知道事件类型。
        addHeader(record, "eventVersion", Integer.toString(event.eventVersion())); // 排查 schema version。
        addHeader(record, "aggregateType", event.aggregateType());              // 说明事实来自哪类 aggregate。
        addHeader(record, "aggregateId", event.aggregateId());                  // 定位具体业务对象。

        try {
            // 阶段 3：真正发送并等待 broker ack。KafkaTemplate.send 本身先返回 CompletableFuture；
            // get(timeout) 才把本 worker 等到“broker 确认”或明确失败。
            // 如果 fire-and-forget，broker 实际失败时 Outbox 仍可能误以为消息已发出。
            // 等待 Kafka acknowledgement 后才把 Outbox row 标记为 PUBLISHED。
            // 这里仍不是分布式事务，所以整体语义仍是 at-least-once。
            kafkaTemplate.send(record)                         // 异步把 record 交给 Kafka producer。
                    .get(timeout.toMillis(), TimeUnit.MILLISECONDS); // 当前 Outbox worker 同步等待 broker ack。
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
