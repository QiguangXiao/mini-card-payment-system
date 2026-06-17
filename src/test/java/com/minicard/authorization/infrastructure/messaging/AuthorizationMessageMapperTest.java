package com.minicard.authorization.infrastructure.messaging;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minicard.authorization.domain.Money;
import com.minicard.authorization.domain.event.AuthorizationApprovedDomainEvent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuthorizationMessageMapperTest {

    @Test
    void mapsApprovedDomainEventToApprovedMessagePayload() {
        Instant now = Instant.parse("2026-06-14T00:00:00Z");
        UUID eventId = UUID.randomUUID();
        UUID authorizationId = UUID.randomUUID();
        AuthorizationMessageMapper mapper = new AuthorizationMessageMapper(new ObjectMapper());

        AuthorizationMessage message = mapper.map(new AuthorizationApprovedDomainEvent(
                authorizationId,
                "card-123",
                new Money(new BigDecimal("100.00"), Currency.getInstance("JPY")),
                now,
                now.plusSeconds(7 * 24 * 60 * 60)
        ), eventId);

        assertThat(message.eventId()).isEqualTo(eventId);
        assertThat(message.aggregateId()).isEqualTo(authorizationId);
        assertThat(message.partitionKey()).isEqualTo(authorizationId.toString());
        assertThat(message.eventType()).isEqualTo("authorization.approved");
        assertThat(message.payload().get("authorizationId").asText())
                .isEqualTo(authorizationId.toString());
        assertThat(message.payload().get("expiresAt").asText())
                .isEqualTo(now.plusSeconds(7 * 24 * 60 * 60).toString());
    }
}
