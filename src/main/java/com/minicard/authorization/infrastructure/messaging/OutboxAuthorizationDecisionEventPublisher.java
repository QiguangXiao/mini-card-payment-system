package com.minicard.authorization.infrastructure.messaging;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minicard.authorization.application.AuthorizationDecisionEventPublisher;
import com.minicard.authorization.domain.Authorization;
import com.minicard.messaging.event.AuthorizationDecidedEvent;
import com.minicard.messaging.event.IntegrationEventEnvelope;
import com.minicard.messaging.outbox.domain.OutboxEvent;
import com.minicard.messaging.outbox.domain.OutboxEventRepository;
import org.springframework.stereotype.Component;

/**
 * Authorization outbound adapter that translates the aggregate into an
 * integration event and stores it through the shared Outbox mechanism.
 */
@Component
public class OutboxAuthorizationDecisionEventPublisher
        implements AuthorizationDecisionEventPublisher {

    private static final String AGGREGATE_TYPE = "Authorization";

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public OutboxAuthorizationDecisionEventPublisher(
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
        Instant decidedAt = authorization.decidedAt()
                .orElseThrow(() -> new IllegalArgumentException(
                        "only a decided authorization can produce an integration event"
                ));
        UUID eventId = UUID.randomUUID();
        AuthorizationDecidedEvent payload = new AuthorizationDecidedEvent(
                authorization.id(),
                authorization.cardId(),
                // Decimal text preserves financial precision and scale across
                // JSON stores and consumers that might otherwise use floating point.
                authorization.requestedAmount().amount().toPlainString(),
                authorization.requestedAmount().currency().getCurrencyCode(),
                authorization.status().name(),
                authorization.declineReason().map(Enum::name).orElse(null),
                decidedAt
        );
        IntegrationEventEnvelope<AuthorizationDecidedEvent> envelope =
                new IntegrationEventEnvelope<>(
                        eventId,
                        AuthorizationDecidedEvent.EVENT_TYPE,
                        AuthorizationDecidedEvent.EVENT_VERSION,
                        decidedAt,
                        payload
                );

        // AuthorizationService owns the transaction. The authorization decision,
        // credit reservation, and event intent commit or roll back together.
        outboxEventRepository.insert(OutboxEvent.pending(
                eventId,
                AGGREGATE_TYPE,
                authorization.id().toString(),
                AuthorizationDecidedEvent.EVENT_TYPE,
                AuthorizationDecidedEvent.EVENT_VERSION,
                authorization.id().toString(),
                serialize(envelope),
                Instant.now(clock)
        ));
    }

    private String serialize(IntegrationEventEnvelope<AuthorizationDecidedEvent> envelope) {
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("failed to serialize authorization event", exception);
        }
    }
}
