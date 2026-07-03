package com.minicard.messaging.kafka;

import java.time.Instant;
import java.time.format.DateTimeParseException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minicard.messaging.event.IntegrationEvent;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.stereotype.Component;

/**
 * Kafka inbound adapter 共用的 integration event reader。
 *
 * <p>关键词：消息读取, 契约校验, Kafka header, integration event reader,
 * contract validation, JSON envelope, メッセージ読取(メッセージよみとり),
 * 契約検証(けいやくけんしょう)。</p>
 *
 * <p>契约分工：<b>consumer correctness 只依赖 self-describing envelope</b>——永远解析 body，
 * 用 body 的 eventType 决定 dispatch。header 是纯 observability 元数据(kcat/DLT/日志排查
 * 不解析 JSON 就能看到消息身份)，不参与 correctness，所以这里既不读 header，
 * 也不做 header/body 一致性校验；缺 header 的手工 replay(console producer)照常可消费。</p>
 *
 * <p>曾考虑用 eventType header 在解析前跳过无关消息（省 Jackson 解析；envelope 损坏的
 * 无关消息不进本 consumer 的 DLT），被否决：感兴趣类型要在 hint 参数和 body 分支两处维护；
 * 且引入"信任 header"语义——header 说谎会错跳，而被跳过的消息在 consumer 侧不可检出。
 * 在单一 outbox producer、消息很小、topic 已按业务拆分的前提下，这点收益买不回复杂度。
 * 另外 Kafka fetch 是整批 record(含 value)拉回，header 过滤本来就省不了网络 IO。</p>
 *
 * <p>具体 consumer 自己根据 eventType 读取 JsonNode payload，避免为每种消息创建 payload class。</p>
 */
@Component
// @RequiredArgsConstructor 只为 final fields 生成 constructor，适合无状态 infrastructure component。
// 如果加 @Data，会额外生成 setter，让 ObjectMapper dependency 看起来可变，反而降低可读性。
@RequiredArgsConstructor
public class IntegrationEventReader {

    /** Jackson ObjectMapper 负责 JSON envelope 反序列化。 */
    private final ObjectMapper objectMapper;

    /**
     * 读取 Kafka record 并校验 transport contract。
     */
    public IntegrationEvent read(ConsumerRecord<String, String> record) {
        try {
            // value 是 Kafka message body。这里永远解析 body envelope，再由 listener 用 eventType dispatch。
            // 如果 JSON 坏了，会抛 EventContractException，交给 KafkaMessagingConfiguration 配置的
            // DefaultErrorHandler：contract failure 不重试，直接发到对应 consumer 的 DLT。
            IntegrationEvent event = objectMapper.readValue(record.value(), IntegrationEvent.class);
            // 集中 validate transport contract，consumer 就能专注业务字段。
            // 如果每个 listener 自己解析/校验，contract failure 会变成不一致的异常和重试行为。
            validate(event);
            return event;
        } catch (JsonProcessingException exception) {
            // JSON 格式错误是永久 contract failure，重试同一消息也不会成功。
            throw new EventContractException("integration event JSON is invalid", exception);
        }
    }

    /**
     * 校验 envelope 自身的必填字段；header 不参与 correctness，所以这里不读它。
     */
    private void validate(IntegrationEvent event) {
        if (event.eventId() == null || event.payload() == null || event.payload().isNull()) {
            // 缺 eventId/payload 时无法做 idempotency 和业务解析，必须拒绝。
            // 如果放进业务 service，缺 eventId 会让 Inbox 无法去重，重复投递就可能重复写业务表。
            throw new EventContractException("eventId and payload are required");
        }
        if (event.eventType() == null || event.eventType().isBlank()) {
            // eventType 是 dispatch key。缺它时 consumer 不知道这条消息属于哪个业务 contract。
            throw new EventContractException("eventType is required");
        }
        if (event.eventVersion() < 1) {
            // 版本号用于未来 schema 演进。非法版本不要猜测兼容，否则 replay 时会产生不可解释的数据。
            throw new EventContractException("eventVersion must be positive");
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
