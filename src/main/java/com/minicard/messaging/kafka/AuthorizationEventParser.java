package com.minicard.messaging.kafka;

import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minicard.messaging.event.AuthorizationDecidedEvent;
import com.minicard.messaging.event.IntegrationEventEnvelope;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.stereotype.Component;

/**
 * 把 Kafka message contract 转换成消费者内部使用的强类型事件。
 *
 * <p>Contract validation 集中在这里，保证 notification/risk 等 consumer
 * 对 malformed 或 unsupported event 的处理一致。永久性错误会进入对应 DLT。</p>
 */
@Component
public class AuthorizationEventParser {

    private final ObjectMapper objectMapper;
    private final JavaType eventType;

    public AuthorizationEventParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.eventType = objectMapper.getTypeFactory().constructParametricType(
                IntegrationEventEnvelope.class,
                AuthorizationDecidedEvent.class
        );
    }

    public IntegrationEventEnvelope<AuthorizationDecidedEvent> parse(
            ConsumerRecord<String, String> record
    ) {
        try {
            IntegrationEventEnvelope<AuthorizationDecidedEvent> event =
                    objectMapper.readValue(record.value(), eventType);
            // 先反序列化再校验 envelope/header，避免每个 consumer 重复写一套防御逻辑。
            validate(record, event);
            return event;
        } catch (JsonProcessingException exception) {
            throw new EventContractException("authorization event JSON is invalid", exception);
        }
    }

    private void validate(
            ConsumerRecord<String, String> record,
            IntegrationEventEnvelope<AuthorizationDecidedEvent> event
    ) {
        if (!AuthorizationDecidedEvent.EVENT_TYPE.equals(event.eventType())) {
            throw new EventContractException("unsupported event type " + event.eventType());
        }
        if (event.eventVersion() != AuthorizationDecidedEvent.EVENT_VERSION) {
            throw new EventContractException(
                    "unsupported authorization event version " + event.eventVersion()
            );
        }
        if (event.eventId() == null || event.payload() == null) {
            throw new EventContractException("eventId and payload are required");
        }

        Header eventIdHeader = record.headers().lastHeader("eventId");
        if (eventIdHeader == null
                || !event.eventId().toString().equals(
                        new String(eventIdHeader.value(), StandardCharsets.UTF_8)
                )) {
            // header 和 payload 的 eventId 必须一致，避免 partition/trace 信息和业务幂等键分裂。
            throw new EventContractException("eventId header does not match payload");
        }
    }
}
