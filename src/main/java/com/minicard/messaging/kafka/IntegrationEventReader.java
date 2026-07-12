package com.minicard.messaging.kafka;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Currency;
import java.util.UUID;

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
 * <p>基础背景：{@code ConsumerRecord} 是 Kafka 交给 listener 的传输对象，包含 topic、partition、offset、
 * key、headers 和 value。项目把 value 设计成统一的 {@code IntegrationEvent} envelope：外层放
 * eventId/eventType/eventVersion/occurredAt，payload 才放具体业务字段。共享 envelope 让所有 listener
 * 先用同一套规则验证消息身份、版本和幂等键。</p>
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
            // 阶段 1：把 record.value() 的 JSON 反序列化成统一 envelope；此时还没有执行业务逻辑。
            // 如果 JSON 坏了，会抛 EventContractException，交给 KafkaConsumerConfiguration 配置的
            // DefaultErrorHandler：contract failure 不重试，直接发到对应 consumer 的 DLT。
            IntegrationEvent event = objectMapper.readValue(
                    record.value(),          // Kafka value 是 producer 写入的 JSON 字符串。
                    IntegrationEvent.class   // 先只解析稳定 envelope，payload 暂时保留 JsonNode。
            );
            // 阶段 2：集中校验 transport contract，consumer 才能专注业务字段。
            // 如果每个 listener 自己解析/校验，contract failure 会变成不一致的异常和重试行为。
            validate(event); // validate 失败会抛 not-retryable EventContractException。
            return event;    // 返回后由具体 listener 判断 eventType 并解析业务 payload。
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
     * 读取必填 UUID 字段，并把确定性的格式错误统一归类为 permanent contract failure。
     */
    public UUID requiredUuid(JsonNode root, String fieldName) {
        String value = requiredText(root, fieldName);
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            // 如果让 IllegalArgumentException 直接逃逸，Kafka 会把不会自愈的坏 UUID 当成瞬时异常空转 retry。
            throw new EventContractException(fieldName + " must be a valid UUID", exception);
        }
    }

    /**
     * 读取必填十进制字段；这里只验证 transport format，正数、币种精度等业务规则仍由 domain 负责。
     */
    public BigDecimal requiredDecimal(JsonNode root, String fieldName) {
        String value = requiredText(root, fieldName);
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException exception) {
            // 同一 payload 重试不会把非法数字变合法，必须标成 contract failure 后直接进入 DLT。
            throw new EventContractException(fieldName + " must be a valid decimal", exception);
        }
    }

    /**
     * 读取必填 ISO 4217 币种字段；账户币种是否匹配属于业务校验，不放在共享 reader。
     */
    public Currency requiredCurrency(JsonNode root, String fieldName) {
        String value = requiredText(root, fieldName);
        try {
            return Currency.getInstance(value);
        } catch (IllegalArgumentException exception) {
            // 非法币种代码是永久消息错误；不包装时会被 DefaultErrorHandler 无意义地重试两次。
            throw new EventContractException(fieldName + " must be a valid ISO 4217 currency", exception);
        }
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
