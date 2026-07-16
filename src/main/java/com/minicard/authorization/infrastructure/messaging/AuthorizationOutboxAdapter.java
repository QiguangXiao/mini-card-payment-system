package com.minicard.authorization.infrastructure.messaging;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.minicard.authorization.application.AuthorizationDomainEventAppender;
import com.minicard.shared.domain.Money;
import com.minicard.authorization.domain.event.AuthorizationApprovedDomainEvent;
import com.minicard.authorization.domain.event.AuthorizationDeclinedDomainEvent;
import com.minicard.authorization.domain.event.AuthorizationDomainEvent;
import com.minicard.authorization.domain.event.AuthorizationExpiredDomainEvent;
import com.minicard.authorization.domain.event.AuthorizationPostedDomainEvent;
import com.minicard.messaging.event.IntegrationEvent;
import com.minicard.messaging.outbox.OutboxEvent;
import com.minicard.messaging.outbox.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Authorization domain event 的 Outbox adapter。
 *
 * <p>Adapter 是 domain 和 messaging 机制之间的薄边界：生成 outbound eventId、
 * 映射 eventType/payload、序列化 envelope，并写入 Outbox。这里不再拆额外 mapper，
 * 因为当前 contract 很小，过度包装反而让学习路径变长。</p>
 */
@Component
@RequiredArgsConstructor
public class AuthorizationOutboxAdapter implements AuthorizationDomainEventAppender {

    private static final String AGGREGATE_TYPE = "Authorization";
    private static final String AUTHORIZATION_APPROVED = "authorization.approved";
    private static final String AUTHORIZATION_DECLINED = "authorization.declined";
    private static final String AUTHORIZATION_EXPIRED = "authorization.expired";
    private static final String AUTHORIZATION_POSTED = "authorization.posted";
    private static final int EVENT_VERSION = 1;

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Override
    public void append(AuthorizationDomainEvent event) {
        // eventId 属于 outbound message，不属于 domain event。
        // 由 adapter 生成，确保 envelope、Kafka header 和 outbox row 使用同一个幂等键。
        UUID eventId = UUID.randomUUID();
        String eventType = eventType(event);
        JsonNode payload = payload(event);
        // IntegrationEvent 不是再造一个业务 DTO，而是 Kafka message body 的统一 envelope。
        // OutboxEvent 的 eventType/eventVersion columns 方便本服务查询、routing、retry；
        // envelope 里的 eventId/eventType/version 让消息离开本服务后仍然 self-describing，
        // DLT/replay/外部 consumer 不必依赖 outbox_events 表才能知道这条消息是什么。
        // 如果只发送裸 payload，consumer 要从 topic/header/代码约定猜 schema，版本演进和排查都会变脆。
        IntegrationEvent envelope = new IntegrationEvent(
                eventId,
                eventType,
                EVENT_VERSION,
                event.occurredAt(),
                payload
        );

        // Outbox row 和业务状态在同一 MySQL transaction 内提交；
        // Kafka 发送由通用 outbox worker 后续完成。
        outboxEventRepository.insert(OutboxEvent.pending(
                eventId,
                AGGREGATE_TYPE,
                event.authorizationId().toString(),
                eventType,
                EVENT_VERSION,
                event.authorizationId().toString(),
                serialize(envelope),
                Instant.now(clock)
        ));
    }

    private String eventType(AuthorizationDomainEvent event) {
        if (event instanceof AuthorizationApprovedDomainEvent) {
            return AUTHORIZATION_APPROVED;
        }
        if (event instanceof AuthorizationDeclinedDomainEvent) {
            return AUTHORIZATION_DECLINED;
        }
        if (event instanceof AuthorizationExpiredDomainEvent) {
            return AUTHORIZATION_EXPIRED;
        }
        if (event instanceof AuthorizationPostedDomainEvent) {
            return AUTHORIZATION_POSTED;
        }
        throw new IllegalArgumentException("unsupported authorization domain event " + event.getClass());
    }

    private JsonNode payload(AuthorizationDomainEvent event) {
        if (event instanceof AuthorizationApprovedDomainEvent approved) {
            ObjectNode payload = payloadBase(
                    approved.authorizationId(),
                    approved.cardId(),
                    approved.requestedAmount()
            );
            payload.put("approvedAt", approved.occurredAt().toString());
            payload.put("expiresAt", approved.expiresAt().toString());
            return payload;
        }
        if (event instanceof AuthorizationDeclinedDomainEvent declined) {
            ObjectNode payload = payloadBase(
                    declined.authorizationId(),
                    declined.cardId(),
                    declined.requestedAmount()
            );
            payload.put("declineReason", declined.declineReason().name());
            payload.put("declinedAt", declined.occurredAt().toString());
            return payload;
        }
        if (event instanceof AuthorizationExpiredDomainEvent expired) {
            ObjectNode payload = payloadBase(
                    expired.authorizationId(),
                    expired.cardId(),
                    expired.requestedAmount()
            );
            payload.put("expiresAt", expired.expiresAt().toString());
            payload.put("expiredAt", expired.occurredAt().toString());
            return payload;
        }
        if (event instanceof AuthorizationPostedDomainEvent posted) {
            ObjectNode payload = payloadBase(
                    posted.authorizationId(),
                    posted.cardId(),
                    posted.requestedAmount()
            );
            payload.put("postedAt", posted.occurredAt().toString());
            return payload;
        }
        throw new IllegalArgumentException("unsupported authorization domain event " + event.getClass());
    }

    private ObjectNode payloadBase(UUID authorizationId, String cardId, Money requestedAmount) {
        // ObjectNode 手工写 event contract 字段，避免直接序列化 domain event 导致内部字段名变成外部契约。
        // 如果把 domain object 直接丢给 Jackson，以后 domain 重构字段名会意外破坏 Kafka 消息格式。
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("authorizationId", authorizationId.toString());
        payload.put("cardId", cardId);
        // 金额写成 plain string，consumer 再用 BigDecimal(String) 解析，避免 JSON number/double 精度歧义。
        payload.put("amount", requestedAmount.amount().toPlainString());
        payload.put("currency", requestedAmount.currency().getCurrencyCode());
        return payload;
    }

    private String serialize(IntegrationEvent envelope) {
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("failed to serialize authorization message", exception);
        }
    }
}
