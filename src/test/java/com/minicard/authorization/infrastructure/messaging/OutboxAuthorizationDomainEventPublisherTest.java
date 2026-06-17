package com.minicard.authorization.infrastructure.messaging;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.minicard.authorization.domain.AuthorizationDeclineReason;
import com.minicard.authorization.domain.Money;
import com.minicard.authorization.domain.event.AuthorizationApprovedDomainEvent;
import com.minicard.authorization.domain.event.AuthorizationDeclinedDomainEvent;
import com.minicard.authorization.integration.event.AuthorizationApprovedIntegrationEvent;
import com.minicard.authorization.integration.event.AuthorizationDeclinedIntegrationEvent;
import com.minicard.messaging.outbox.domain.OutboxEvent;
import com.minicard.messaging.outbox.domain.OutboxEventRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class OutboxAuthorizationDomainEventPublisherTest {

    private static final Instant NOW = Instant.parse("2026-06-14T00:00:00Z");

    private final ObjectMapper objectMapper =
            new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void approvedDomainEventWritesApprovedIntegrationEvent() throws Exception {
        OutboxEventRepository repository = mock(OutboxEventRepository.class);
        OutboxAuthorizationDomainEventPublisher publisher = publisher(repository);

        publisher.append(new AuthorizationApprovedDomainEvent(
                UUID.randomUUID(),
                "card-123",
                money(),
                NOW,
                NOW.plusSeconds(7 * 24 * 60 * 60)
        ));

        OutboxEvent event = insertedEvent(repository);
        JsonNode payload = objectMapper.readTree(event.payload());
        assertThat(event.eventType()).isEqualTo(AuthorizationApprovedIntegrationEvent.EVENT_TYPE);
        assertThat(payload.get("eventType").asText())
                .isEqualTo(AuthorizationApprovedIntegrationEvent.EVENT_TYPE);
        assertThat(payload.get("payload").has("expiresAt")).isTrue();
        assertThat(payload.get("payload").has("declineReason")).isFalse();
    }

    @Test
    void declinedDomainEventWritesDeclinedIntegrationEvent() throws Exception {
        OutboxEventRepository repository = mock(OutboxEventRepository.class);
        OutboxAuthorizationDomainEventPublisher publisher = publisher(repository);

        publisher.append(new AuthorizationDeclinedDomainEvent(
                UUID.randomUUID(),
                "card-123",
                money(),
                AuthorizationDeclineReason.INSUFFICIENT_AVAILABLE_CREDIT,
                NOW
        ));

        OutboxEvent event = insertedEvent(repository);
        JsonNode payload = objectMapper.readTree(event.payload());
        assertThat(event.eventType()).isEqualTo(AuthorizationDeclinedIntegrationEvent.EVENT_TYPE);
        assertThat(payload.get("eventType").asText())
                .isEqualTo(AuthorizationDeclinedIntegrationEvent.EVENT_TYPE);
        assertThat(payload.get("payload").get("declineReason").asText())
                .isEqualTo("INSUFFICIENT_AVAILABLE_CREDIT");
        assertThat(payload.get("payload").has("expiresAt")).isFalse();
    }

    private OutboxAuthorizationDomainEventPublisher publisher(
            OutboxEventRepository repository
    ) {
        return new OutboxAuthorizationDomainEventPublisher(
                repository,
                objectMapper,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private OutboxEvent insertedEvent(OutboxEventRepository repository) {
        ArgumentCaptor<OutboxEvent> event = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(repository).insert(event.capture());
        return event.getValue();
    }

    private Money money() {
        return new Money(new BigDecimal("100.00"), Currency.getInstance("JPY"));
    }
}
