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
import com.minicard.authorization.domain.event.AuthorizationExpiredDomainEvent;
import com.minicard.authorization.domain.event.AuthorizationPostedDomainEvent;
import com.minicard.messaging.outbox.OutboxEvent;
import com.minicard.messaging.outbox.OutboxEventRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AuthorizationOutboxAdapterTest {

    private static final Instant NOW = Instant.parse("2026-06-14T00:00:00Z");

    private final ObjectMapper objectMapper =
            new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void approvedDomainEventWritesApprovedOutboxMessage() throws Exception {
        OutboxEventRepository repository = mock(OutboxEventRepository.class);
        AuthorizationOutboxAdapter adapter = adapter(repository);

        adapter.append(new AuthorizationApprovedDomainEvent(
                UUID.randomUUID(),
                "card-123",
                money(),
                NOW,
                NOW.plusSeconds(7 * 24 * 60 * 60)
        ));

        OutboxEvent event = insertedEvent(repository);
        JsonNode payload = objectMapper.readTree(event.payload());
        assertThat(event.eventType()).isEqualTo("authorization.approved");
        assertThat(payload.get("eventType").asText())
                .isEqualTo("authorization.approved");
        assertThat(payload.get("payload").has("expiresAt")).isTrue();
        assertThat(payload.get("payload").has("declineReason")).isFalse();
    }

    @Test
    void declinedDomainEventWritesDeclinedOutboxMessage() throws Exception {
        OutboxEventRepository repository = mock(OutboxEventRepository.class);
        AuthorizationOutboxAdapter adapter = adapter(repository);

        adapter.append(new AuthorizationDeclinedDomainEvent(
                UUID.randomUUID(),
                "card-123",
                money(),
                AuthorizationDeclineReason.INSUFFICIENT_AVAILABLE_CREDIT,
                NOW
        ));

        OutboxEvent event = insertedEvent(repository);
        JsonNode payload = objectMapper.readTree(event.payload());
        assertThat(event.eventType()).isEqualTo("authorization.declined");
        assertThat(payload.get("eventType").asText())
                .isEqualTo("authorization.declined");
        assertThat(payload.get("payload").get("declineReason").asText())
                .isEqualTo("INSUFFICIENT_AVAILABLE_CREDIT");
        assertThat(payload.get("payload").has("expiresAt")).isFalse();
    }

    @Test
    void expiredDomainEventWritesExpiredOutboxMessage() throws Exception {
        OutboxEventRepository repository = mock(OutboxEventRepository.class);
        AuthorizationOutboxAdapter adapter = adapter(repository);

        adapter.append(new AuthorizationExpiredDomainEvent(
                UUID.randomUUID(),
                "card-123",
                money(),
                NOW,
                NOW.plusSeconds(1)
        ));

        OutboxEvent event = insertedEvent(repository);
        JsonNode payload = objectMapper.readTree(event.payload());
        assertThat(event.eventType()).isEqualTo("authorization.expired");
        assertThat(payload.get("eventType").asText())
                .isEqualTo("authorization.expired");
        assertThat(payload.get("payload").get("expiredAt").asText())
                .isEqualTo(NOW.plusSeconds(1).toString());
    }

    @Test
    void postedDomainEventWritesPostedOutboxMessage() throws Exception {
        OutboxEventRepository repository = mock(OutboxEventRepository.class);
        AuthorizationOutboxAdapter adapter = adapter(repository);

        adapter.append(new AuthorizationPostedDomainEvent(
                UUID.randomUUID(),
                "card-123",
                money(),
                NOW
        ));

        OutboxEvent event = insertedEvent(repository);
        JsonNode payload = objectMapper.readTree(event.payload());
        assertThat(event.eventType()).isEqualTo("authorization.posted");
        assertThat(payload.get("eventType").asText())
                .isEqualTo("authorization.posted");
        assertThat(payload.get("payload").get("postedAt").asText())
                .isEqualTo(NOW.toString());
    }

    private AuthorizationOutboxAdapter adapter(OutboxEventRepository repository) {
        return new AuthorizationOutboxAdapter(
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
