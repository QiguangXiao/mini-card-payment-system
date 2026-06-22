package com.minicard.transaction.infrastructure.messaging;

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
import com.minicard.transaction.application.CardTransactionDomainEventPublisher;
import com.minicard.transaction.domain.event.CardTransactionDomainEvent;
import com.minicard.transaction.domain.event.CardTransactionPostedDomainEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * CardTransaction domain event 的 Outbox adapter。
 *
 * <p>它是 transaction bounded context 的 outbound adapter：只负责把交易领域事件映射成
 * integration event 并写入 Outbox。Notification 作为未来独立微服务，只能通过 Kafka contract
 * 看到这些事实，不能直接依赖 transaction domain class。</p>
 */
@Component
@RequiredArgsConstructor
public class CardTransactionOutboxAdapter implements CardTransactionDomainEventPublisher {

    private static final String AGGREGATE_TYPE = "CardTransaction";
    private static final String CARD_TRANSACTION_POSTED = "card_transaction.posted";
    private static final int EVENT_VERSION = 1;

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Override
    public void append(CardTransactionDomainEvent event) {
        // eventId 属于 outbound message；cardTransactionId 才是业务 aggregate id。
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

        // Outbox row 和 CardTransaction POSTED 状态在同一个 transaction boundary 内提交。
        outboxEventRepository.insert(OutboxEvent.pending(
                eventId,
                AGGREGATE_TYPE,
                event.cardTransactionId().toString(),
                eventType,
                EVENT_VERSION,
                event.cardTransactionId().toString(),
                serialize(envelope),
                Instant.now(clock)
        ));
    }

    private String eventType(CardTransactionDomainEvent event) {
        if (event instanceof CardTransactionPostedDomainEvent) {
            return CARD_TRANSACTION_POSTED;
        }
        throw new IllegalArgumentException("unsupported card transaction domain event " + event.getClass());
    }

    private JsonNode payload(CardTransactionDomainEvent event) {
        if (event instanceof CardTransactionPostedDomainEvent posted) {
            // 手写 ObjectNode 是为了固定 Kafka contract；不要直接序列化 domain event class。
            // 否则 Java 字段重命名会变成外部消息格式变更。
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("cardTransactionId", posted.cardTransactionId().toString());
            payload.put("networkTransactionId", posted.networkTransactionId());
            payload.put("authorizationId", posted.authorizationId().toString());
            payload.put("cardId", posted.cardId());
            payload.put("creditAccountId", posted.creditAccountId().toString());
            // 金额用 plain string，consumer 用 BigDecimal(String) 解析，避免 JSON number/double 精度问题。
            payload.put("amount", posted.amount().amount().toPlainString());
            payload.put("currency", posted.amount().currency().getCurrencyCode());
            payload.put("postedAt", posted.occurredAt().toString());
            return payload;
        }
        throw new IllegalArgumentException("unsupported card transaction domain event " + event.getClass());
    }

    private String serialize(IntegrationEvent envelope) {
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("failed to serialize card transaction message", exception);
        }
    }
}
