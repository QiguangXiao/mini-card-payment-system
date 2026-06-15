package com.minicard.authorization.infrastructure.messaging;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minicard.authorization.application.AuthorizationExpiryEventPublisher;
import com.minicard.authorization.domain.Authorization;
import com.minicard.messaging.event.AuthorizationExpiredEvent;
import com.minicard.messaging.event.IntegrationEventEnvelope;
import com.minicard.messaging.outbox.domain.OutboxEvent;
import com.minicard.messaging.outbox.domain.OutboxEventRepository;
import org.springframework.stereotype.Component;

/**
 * Outbox adapter for the separate authorization-expired lifecycle event.
 */
@Component
public class OutboxAuthorizationExpiryEventPublisher implements AuthorizationExpiryEventPublisher {

    private static final String AGGREGATE_TYPE = "Authorization";

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public OutboxAuthorizationExpiryEventPublisher(
            OutboxEventRepository outboxEventRepository,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public void append(Authorization authorization) {
        Instant expiresAt = authorization.expiresAt()
                .orElseThrow(() -> new IllegalArgumentException(
                        "expired authorization must have expiresAt"
                ));
        Instant expiredAt = authorization.expiredAt()
                .orElseThrow(() -> new IllegalArgumentException(
                        "expired authorization must have expiredAt"
                ));
        UUID eventId = UUID.randomUUID();
        AuthorizationExpiredEvent payload = new AuthorizationExpiredEvent(
                authorization.id(),
                authorization.cardId(),
                authorization.requestedAmount().amount().toPlainString(),
                authorization.requestedAmount().currency().getCurrencyCode(),
                expiresAt,
                expiredAt
        );
        IntegrationEventEnvelope<AuthorizationExpiredEvent> envelope =
                new IntegrationEventEnvelope<>(
                        eventId,
                        AuthorizationExpiredEvent.EVENT_TYPE,
                        AuthorizationExpiredEvent.EVENT_VERSION,
                        expiredAt,
                        payload
                );

        // This insert participates in AuthorizationExpiryService's transaction,
        // so released credit cannot commit without a recoverable event intent.
        outboxEventRepository.insert(OutboxEvent.pending(
                eventId,
                AGGREGATE_TYPE,
                authorization.id().toString(),
                AuthorizationExpiredEvent.EVENT_TYPE,
                AuthorizationExpiredEvent.EVENT_VERSION,
                authorization.id().toString(),
                serialize(envelope),
                Instant.now(clock)
        ));
    }

    private String serialize(IntegrationEventEnvelope<AuthorizationExpiredEvent> envelope) {
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("failed to serialize authorization expiry event", exception);
        }
    }
}
