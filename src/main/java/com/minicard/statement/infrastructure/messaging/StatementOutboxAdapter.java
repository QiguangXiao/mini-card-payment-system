package com.minicard.statement.infrastructure.messaging;

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
import com.minicard.statement.application.StatementDomainEventPublisher;
import com.minicard.statement.domain.event.StatementClosedDomainEvent;
import com.minicard.statement.domain.event.StatementDomainEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Statement domain event 的 Outbox adapter。
 *
 * <p>它是 statement bounded context 的 outbound adapter：只负责把账单领域事件映射成
 * integration event 并写入 Outbox。Kafka 发布、重试和 at-least-once 语义继续由通用 Outbox worker 承担。
 * Notification 作为未来独立微服务，只能通过 Kafka contract 看到这些事实，不能直接依赖 statement domain class。</p>
 */
@Component
@RequiredArgsConstructor
public class StatementOutboxAdapter implements StatementDomainEventPublisher {

    private static final String AGGREGATE_TYPE = "Statement";
    private static final String STATEMENT_CLOSED = "statement.closed";
    private static final int EVENT_VERSION = 1;

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Override
    public void append(StatementDomainEvent event) {
        // eventId 属于 outbound message；statementId 才是账单 aggregate id。
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

        // Outbox row 和 statement/line/billed-mark/due-job 在同一个 transaction boundary 内提交。
        // partitionKey 用 creditAccountId，保证同一账户的账单/还款/提醒按账户有序消费。
        outboxEventRepository.insert(OutboxEvent.pending(
                eventId,
                AGGREGATE_TYPE,
                event.statementId().toString(),
                eventType,
                EVENT_VERSION,
                event.creditAccountId().toString(),
                serialize(envelope),
                Instant.now(clock)
        ));
    }

    private String eventType(StatementDomainEvent event) {
        // instanceof pattern matching 保持事件类型分支显式。
        // 新增 statement event 若没有映射成 integration contract，会在这里 fail fast，而不是静默丢事件。
        if (event instanceof StatementClosedDomainEvent) {
            return STATEMENT_CLOSED;
        }
        throw new IllegalArgumentException("unsupported statement domain event " + event.getClass());
    }

    private JsonNode payload(StatementDomainEvent event) {
        if (event instanceof StatementClosedDomainEvent closed) {
            // 手写 ObjectNode 固定 Kafka contract；不要直接序列化 domain event record，
            // 否则 Java 字段重命名会变成外部消息格式变更。
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("statementId", closed.statementId().toString());
            payload.put("creditAccountId", closed.creditAccountId().toString());
            // LocalDate 序列化为 ISO yyyy-MM-dd；账期/到期日属于业务展示字段，consumer 直接按字符串解析。
            payload.put("periodStart", closed.periodStart().toString());
            payload.put("periodEnd", closed.periodEnd().toString());
            payload.put("dueDate", closed.dueDate().toString());
            // 金额用 plain string，consumer 用 BigDecimal(String) 解析，避免 JSON number/double 精度问题。
            payload.put("totalAmount", closed.totalAmount().amount().toPlainString());
            payload.put("currency", closed.totalAmount().currency().getCurrencyCode());
            payload.put("minimumPaymentAmount", closed.minimumPaymentAmount().amount().toPlainString());
            payload.put("transactionCount", closed.transactionCount());
            payload.put("closedAt", closed.occurredAt().toString());
            return payload;
        }
        throw new IllegalArgumentException("unsupported statement domain event " + event.getClass());
    }

    private String serialize(IntegrationEvent envelope) {
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("failed to serialize statement message", exception);
        }
    }
}
