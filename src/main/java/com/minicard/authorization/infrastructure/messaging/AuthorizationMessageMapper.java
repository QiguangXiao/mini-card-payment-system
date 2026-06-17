package com.minicard.authorization.infrastructure.messaging;

import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.minicard.authorization.domain.Money;
import com.minicard.authorization.domain.event.AuthorizationApprovedDomainEvent;
import com.minicard.authorization.domain.event.AuthorizationDeclinedDomainEvent;
import com.minicard.authorization.domain.event.AuthorizationDomainEvent;
import com.minicard.authorization.domain.event.AuthorizationExpiredDomainEvent;
import org.springframework.stereotype.Component;

/**
 * Authorization domain event -> outbound message contract 的纯转换器。
 *
 * <p>Mapper 不知道 Outbox 表、Kafka client、transaction boundary。
 * 这样“业务事实如何对外表达”和“消息如何可靠投递”可以分别解释和测试。</p>
 */
@Component
class AuthorizationMessageMapper {

    static final String AUTHORIZATION_APPROVED = "authorization.approved";
    static final String AUTHORIZATION_DECLINED = "authorization.declined";
    static final String AUTHORIZATION_EXPIRED = "authorization.expired";
    static final int EVENT_VERSION = 1;

    private final ObjectMapper objectMapper;

    AuthorizationMessageMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    AuthorizationMessage map(AuthorizationDomainEvent event, UUID eventId) {
        if (event instanceof AuthorizationApprovedDomainEvent approved) {
            return mapApproved(approved, eventId);
        }
        if (event instanceof AuthorizationDeclinedDomainEvent declined) {
            return mapDeclined(declined, eventId);
        }
        if (event instanceof AuthorizationExpiredDomainEvent expired) {
            return mapExpired(expired, eventId);
        }
        throw new IllegalArgumentException("unsupported authorization domain event " + event.getClass());
    }

    private AuthorizationMessage mapApproved(
            AuthorizationApprovedDomainEvent event,
            UUID eventId
    ) {
        ObjectNode payload = payloadBase(
                event.authorizationId(),
                event.cardId(),
                event.requestedAmount()
        );
        payload.put("approvedAt", event.occurredAt().toString());
        payload.put("expiresAt", event.expiresAt().toString());
        return message(
                event,
                eventId,
                AUTHORIZATION_APPROVED,
                payload
        );
    }

    private AuthorizationMessage mapDeclined(
            AuthorizationDeclinedDomainEvent event,
            UUID eventId
    ) {
        ObjectNode payload = payloadBase(
                event.authorizationId(),
                event.cardId(),
                event.requestedAmount()
        );
        payload.put("declineReason", event.declineReason().name());
        payload.put("declinedAt", event.occurredAt().toString());
        return message(
                event,
                eventId,
                AUTHORIZATION_DECLINED,
                payload
        );
    }

    private AuthorizationMessage mapExpired(
            AuthorizationExpiredDomainEvent event,
            UUID eventId
    ) {
        ObjectNode payload = payloadBase(
                event.authorizationId(),
                event.cardId(),
                event.requestedAmount()
        );
        payload.put("expiresAt", event.expiresAt().toString());
        payload.put("expiredAt", event.occurredAt().toString());
        return message(
                event,
                eventId,
                AUTHORIZATION_EXPIRED,
                payload
        );
    }

    private AuthorizationMessage message(
            AuthorizationDomainEvent event,
            UUID eventId,
            String eventType,
            JsonNode payload
    ) {
        return new AuthorizationMessage(
                eventId,
                event.authorizationId(),
                eventType,
                EVENT_VERSION,
                event.occurredAt(),
                event.authorizationId().toString(),
                payload
        );
    }

    private ObjectNode payloadBase(UUID authorizationId, String cardId, Money requestedAmount) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("authorizationId", authorizationId.toString());
        payload.put("cardId", cardId);
        payload.put("amount", amountText(requestedAmount));
        payload.put("currency", currencyCode(requestedAmount));
        return payload;
    }

    private String amountText(Money money) {
        return money.amount().toPlainString();
    }

    private String currencyCode(Money money) {
        return money.currency().getCurrencyCode();
    }
}
