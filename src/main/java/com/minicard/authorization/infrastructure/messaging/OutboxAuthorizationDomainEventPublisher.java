package com.minicard.authorization.infrastructure.messaging;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minicard.authorization.application.AuthorizationDomainEventPublisher;
import com.minicard.authorization.domain.Money;
import com.minicard.authorization.domain.event.AuthorizationApprovedDomainEvent;
import com.minicard.authorization.domain.event.AuthorizationDeclinedDomainEvent;
import com.minicard.authorization.domain.event.AuthorizationDomainEvent;
import com.minicard.authorization.domain.event.AuthorizationExpiredDomainEvent;
import com.minicard.authorization.integration.event.AuthorizationApprovedIntegrationEvent;
import com.minicard.authorization.integration.event.AuthorizationDeclinedIntegrationEvent;
import com.minicard.authorization.integration.event.AuthorizationExpiredIntegrationEvent;
import com.minicard.messaging.event.IntegrationEventEnvelope;
import com.minicard.messaging.outbox.domain.OutboxEvent;
import com.minicard.messaging.outbox.domain.OutboxEventRepository;
import org.springframework.stereotype.Component;

/**
 * Authorization domain event 的 Outbox adapter。
 *
 * <p>Authorization application 只发布 domain event；这里负责映射成 public integration event，
 * 再写入通用 Outbox reliable-message queue。</p>
 */
@Component
public class OutboxAuthorizationDomainEventPublisher
        implements AuthorizationDomainEventPublisher {

    private static final String AGGREGATE_TYPE = "Authorization";

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public OutboxAuthorizationDomainEventPublisher(
            OutboxEventRepository outboxEventRepository,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public void append(AuthorizationDomainEvent event) {
        if (event instanceof AuthorizationApprovedDomainEvent approved) {
            appendApproved(approved);
            return;
        }
        if (event instanceof AuthorizationDeclinedDomainEvent declined) {
            appendDeclined(declined);
            return;
        }
        if (event instanceof AuthorizationExpiredDomainEvent expired) {
            appendExpired(expired);
            return;
        }
        throw new IllegalArgumentException("unsupported authorization domain event " + event.getClass());
    }

    private void appendApproved(AuthorizationApprovedDomainEvent event) {
        UUID eventId = UUID.randomUUID();
        AuthorizationApprovedIntegrationEvent payload =
                new AuthorizationApprovedIntegrationEvent(
                        event.authorizationId(),
                        event.cardId(),
                        amountText(event.requestedAmount()),
                        currencyCode(event.requestedAmount()),
                        event.occurredAt(),
                        event.expiresAt()
                );
        insertOutboxEvent(
                eventId,
                event,
                AuthorizationApprovedIntegrationEvent.EVENT_TYPE,
                AuthorizationApprovedIntegrationEvent.EVENT_VERSION,
                serialize(envelope(
                        eventId,
                        event,
                        AuthorizationApprovedIntegrationEvent.EVENT_TYPE,
                        AuthorizationApprovedIntegrationEvent.EVENT_VERSION,
                        payload
                ))
        );
    }

    private void appendDeclined(AuthorizationDeclinedDomainEvent event) {
        UUID eventId = UUID.randomUUID();
        AuthorizationDeclinedIntegrationEvent payload =
                new AuthorizationDeclinedIntegrationEvent(
                        event.authorizationId(),
                        event.cardId(),
                        amountText(event.requestedAmount()),
                        currencyCode(event.requestedAmount()),
                        event.declineReason().name(),
                        event.occurredAt()
                );
        insertOutboxEvent(
                eventId,
                event,
                AuthorizationDeclinedIntegrationEvent.EVENT_TYPE,
                AuthorizationDeclinedIntegrationEvent.EVENT_VERSION,
                serialize(envelope(
                        eventId,
                        event,
                        AuthorizationDeclinedIntegrationEvent.EVENT_TYPE,
                        AuthorizationDeclinedIntegrationEvent.EVENT_VERSION,
                        payload
                ))
        );
    }

    private void appendExpired(AuthorizationExpiredDomainEvent event) {
        UUID eventId = UUID.randomUUID();
        AuthorizationExpiredIntegrationEvent payload =
                new AuthorizationExpiredIntegrationEvent(
                        event.authorizationId(),
                        event.cardId(),
                        amountText(event.requestedAmount()),
                        currencyCode(event.requestedAmount()),
                        event.expiresAt(),
                        event.occurredAt()
                );
        insertOutboxEvent(
                eventId,
                event,
                AuthorizationExpiredIntegrationEvent.EVENT_TYPE,
                AuthorizationExpiredIntegrationEvent.EVENT_VERSION,
                serialize(envelope(
                        eventId,
                        event,
                        AuthorizationExpiredIntegrationEvent.EVENT_TYPE,
                        AuthorizationExpiredIntegrationEvent.EVENT_VERSION,
                        payload
                ))
        );
    }

    private <T> IntegrationEventEnvelope<T> envelope(
            UUID eventId,
            AuthorizationDomainEvent event,
            String eventType,
            int eventVersion,
            T payload
    ) {
        return new IntegrationEventEnvelope<>(
                eventId,
                eventType,
                eventVersion,
                event.occurredAt(),
                payload
        );
    }

    private void insertOutboxEvent(
            UUID eventId,
            AuthorizationDomainEvent domainEvent,
            String eventType,
            int eventVersion,
            String payload
    ) {
        // Outbox row 和业务状态在同一 MySQL transaction 内提交。
        outboxEventRepository.insert(OutboxEvent.pending(
                eventId,
                AGGREGATE_TYPE,
                domainEvent.authorizationId().toString(),
                eventType,
                eventVersion,
                domainEvent.authorizationId().toString(),
                payload,
                Instant.now(clock)
        ));
    }

    private String serialize(IntegrationEventEnvelope<?> envelope) {
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("failed to serialize authorization integration event", exception);
        }
    }

    private String amountText(Money money) {
        return money.amount().toPlainString();
    }

    private String currencyCode(Money money) {
        return money.currency().getCurrencyCode();
    }
}
