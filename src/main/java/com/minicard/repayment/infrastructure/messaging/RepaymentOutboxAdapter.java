package com.minicard.repayment.infrastructure.messaging;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.minicard.messaging.event.IntegrationEvent;
import com.minicard.messaging.outbox.OutboxEvent;
import com.minicard.messaging.outbox.OutboxEventRepository;
import com.minicard.repayment.application.RepaymentDomainEventPublisher;
import com.minicard.repayment.domain.event.RepaymentDomainEvent;
import com.minicard.repayment.domain.event.RepaymentReceivedDomainEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Repayment domain event 的 Outbox adapter。
 *
 * <p>它只负责把 repayment 领域事实映射成 integration event contract。
 * Kafka 发布、重试和 at-least-once 语义继续由通用 Outbox worker 承担。</p>
 */
@Component
@RequiredArgsConstructor
public class RepaymentOutboxAdapter implements RepaymentDomainEventPublisher {

    private static final String AGGREGATE_TYPE = "Repayment";
    private static final String REPAYMENT_RECEIVED = "repayment.received";
    private static final int EVENT_VERSION = 1;

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Override
    public void append(RepaymentDomainEvent event) {
        // eventId 属于 outbound message；repaymentId 才是还款 aggregate id。
        UUID eventId = UUID.randomUUID();
        String eventType = eventType(event);
        JsonNode payload = payload(event);
        IntegrationEvent envelope = new IntegrationEvent(
                eventId,
                eventType,
                EVENT_VERSION,
                event.occurredAt(),
                payload
        );

        // partitionKey 用 creditAccountId，保证同一账户下还款/后续提醒按账户有序消费。
        outboxEventRepository.insert(OutboxEvent.pending(
                eventId,
                AGGREGATE_TYPE,
                event.repaymentId().toString(),
                eventType,
                EVENT_VERSION,
                event.creditAccountId().toString(),
                serialize(envelope),
                Instant.now(clock)
        ));
    }

    private String eventType(RepaymentDomainEvent event) {
        // instanceof pattern matching 保持事件类型分支显式。
        // 如果新增 repayment event 没有映射成 integration contract，会在这里 fail fast。
        if (event instanceof RepaymentReceivedDomainEvent) {
            return REPAYMENT_RECEIVED;
        }
        throw new IllegalArgumentException("unsupported repayment domain event " + event.getClass());
    }

    private JsonNode payload(RepaymentDomainEvent event) {
        if (event instanceof RepaymentReceivedDomainEvent received) {
            // 手工 ObjectNode 固定下游消息字段；不要直接序列化 domain event record。
            // domain 字段重命名不应该无意改变 Kafka contract。
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("repaymentId", received.repaymentId().toString());
            payload.put("statementId", received.statementId().toString());
            payload.put("creditAccountId", received.creditAccountId().toString());
            payload.put("amount", received.amount().amount().toPlainString());
            payload.put("currency", received.amount().currency().getCurrencyCode());
            payload.put("statementPaidAmount", received.statementPaidAmount().amount().toPlainString());
            payload.put("statementRemainingAmount", received.statementRemainingAmount().amount().toPlainString());
            payload.put("receivedAt", received.occurredAt().toString());
            return payload;
        }
        throw new IllegalArgumentException("unsupported repayment domain event " + event.getClass());
    }

    private String serialize(IntegrationEvent envelope) {
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("failed to serialize repayment message", exception);
        }
    }
}
