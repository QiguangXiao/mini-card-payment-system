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
 * <p>StatementService 只发布“账单已关闭”这个业务事实；这里负责把 domain event
 * 映射成 integration event contract，并写入 Outbox 等待 reliable delivery。</p>
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

        // partitionKey 用 creditAccountId，保证同一账户的账单事件在 Kafka 内有序。
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
        if (event instanceof StatementClosedDomainEvent) {
            return STATEMENT_CLOSED;
        }
        throw new IllegalArgumentException("unsupported statement domain event " + event.getClass());
    }

    private JsonNode payload(StatementDomainEvent event) {
        if (event instanceof StatementClosedDomainEvent closed) {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("statementId", closed.statementId().toString());
            payload.put("creditAccountId", closed.creditAccountId().toString());
            payload.put("periodStart", closed.periodStart().toString());
            payload.put("periodEnd", closed.periodEnd().toString());
            payload.put("dueDate", closed.dueDate().toString());
            payload.put("totalAmount", closed.totalAmount().amount().toPlainString());
            payload.put("minimumPaymentAmount", closed.minimumPaymentAmount().amount().toPlainString());
            payload.put("currency", closed.totalAmount().currency().getCurrencyCode());
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
