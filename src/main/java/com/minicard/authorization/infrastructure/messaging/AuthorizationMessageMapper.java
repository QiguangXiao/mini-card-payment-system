package com.minicard.authorization.infrastructure.messaging;

import java.util.UUID;

import com.minicard.authorization.domain.Money;
import com.minicard.authorization.domain.event.AuthorizationApprovedDomainEvent;
import com.minicard.authorization.domain.event.AuthorizationDeclinedDomainEvent;
import com.minicard.authorization.domain.event.AuthorizationDomainEvent;
import com.minicard.authorization.domain.event.AuthorizationExpiredDomainEvent;
import com.minicard.messaging.contract.authorization.AuthorizationApprovedPayload;
import com.minicard.messaging.contract.authorization.AuthorizationDeclinedPayload;
import com.minicard.messaging.contract.authorization.AuthorizationExpiredPayload;
import org.springframework.stereotype.Component;

/**
 * Authorization domain event -> outbound message contract 的纯转换器。
 *
 * <p>Mapper 不知道 Outbox 表、Kafka client、transaction boundary。
 * 这样“业务事实如何对外表达”和“消息如何可靠投递”可以分别解释和测试。</p>
 */
@Component
class AuthorizationMessageMapper {

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
        AuthorizationApprovedPayload payload = new AuthorizationApprovedPayload(
                event.authorizationId(),
                event.cardId(),
                amountText(event.requestedAmount()),
                currencyCode(event.requestedAmount()),
                event.occurredAt(),
                event.expiresAt()
        );
        return message(
                event,
                eventId,
                AuthorizationApprovedPayload.EVENT_TYPE,
                AuthorizationApprovedPayload.EVENT_VERSION,
                payload
        );
    }

    private AuthorizationMessage mapDeclined(
            AuthorizationDeclinedDomainEvent event,
            UUID eventId
    ) {
        AuthorizationDeclinedPayload payload = new AuthorizationDeclinedPayload(
                event.authorizationId(),
                event.cardId(),
                amountText(event.requestedAmount()),
                currencyCode(event.requestedAmount()),
                event.declineReason().name(),
                event.occurredAt()
        );
        return message(
                event,
                eventId,
                AuthorizationDeclinedPayload.EVENT_TYPE,
                AuthorizationDeclinedPayload.EVENT_VERSION,
                payload
        );
    }

    private AuthorizationMessage mapExpired(
            AuthorizationExpiredDomainEvent event,
            UUID eventId
    ) {
        AuthorizationExpiredPayload payload = new AuthorizationExpiredPayload(
                event.authorizationId(),
                event.cardId(),
                amountText(event.requestedAmount()),
                currencyCode(event.requestedAmount()),
                event.expiresAt(),
                event.occurredAt()
        );
        return message(
                event,
                eventId,
                AuthorizationExpiredPayload.EVENT_TYPE,
                AuthorizationExpiredPayload.EVENT_VERSION,
                payload
        );
    }

    private AuthorizationMessage message(
            AuthorizationDomainEvent event,
            UUID eventId,
            String eventType,
            int eventVersion,
            Object payload
    ) {
        return new AuthorizationMessage(
                eventId,
                event.authorizationId(),
                eventType,
                eventVersion,
                event.occurredAt(),
                event.authorizationId().toString(),
                payload
        );
    }

    private String amountText(Money money) {
        return money.amount().toPlainString();
    }

    private String currencyCode(Money money) {
        return money.currency().getCurrencyCode();
    }
}
