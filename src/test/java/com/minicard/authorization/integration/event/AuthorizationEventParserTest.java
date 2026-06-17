package com.minicard.authorization.integration.event;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.minicard.messaging.event.IntegrationEventEnvelope;
import com.minicard.messaging.kafka.EventContractException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthorizationEventParserTest {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final AuthorizationEventParser parser = new AuthorizationEventParser(objectMapper);

    @Test
    void parsesApprovedDecisionEventWithMatchingHeaders() throws Exception {
        IntegrationEventEnvelope<AuthorizationApprovedIntegrationEvent> event = approvedEvent(1);
        ConsumerRecord<String, String> record = record(
                objectMapper.writeValueAsString(event),
                event.eventId(),
                event.eventType()
        );

        AuthorizationDecisionMessage parsed = parser.parseDecisionEvent(record);

        assertThat(parsed.eventId()).isEqualTo(event.eventId());
        assertThat(parsed.eventType()).isEqualTo(AuthorizationApprovedIntegrationEvent.EVENT_TYPE);
        assertThat(parsed.status()).isEqualTo("APPROVED");
        assertThat(parsed.approved()).isTrue();
    }

    @Test
    void parsesDeclinedDecisionEventWithMatchingHeaders() throws Exception {
        IntegrationEventEnvelope<AuthorizationDeclinedIntegrationEvent> event = declinedEvent(1);
        ConsumerRecord<String, String> record = record(
                objectMapper.writeValueAsString(event),
                event.eventId(),
                event.eventType()
        );

        AuthorizationDecisionMessage parsed = parser.parseDecisionEvent(record);

        assertThat(parsed.eventId()).isEqualTo(event.eventId());
        assertThat(parsed.eventType()).isEqualTo(AuthorizationDeclinedIntegrationEvent.EVENT_TYPE);
        assertThat(parsed.status()).isEqualTo("DECLINED");
        assertThat(parsed.approved()).isFalse();
    }

    @Test
    void rejectsUnsupportedDecisionVersionAsPermanentContractFailure() throws Exception {
        IntegrationEventEnvelope<AuthorizationApprovedIntegrationEvent> event = approvedEvent(99);
        ConsumerRecord<String, String> record = record(
                objectMapper.writeValueAsString(event),
                event.eventId(),
                event.eventType()
        );

        assertThatThrownBy(() -> parser.parseDecisionEvent(record))
                .isInstanceOf(EventContractException.class)
                .hasMessage("unsupported authorization event version 99");
    }

    @Test
    void rejectsLifecycleEventWhenConsumerExpectsDecisionEvent() throws Exception {
        Instant now = Instant.parse("2026-06-14T00:00:00Z");
        IntegrationEventEnvelope<AuthorizationExpiredIntegrationEvent> event = new IntegrationEventEnvelope<>(
                UUID.randomUUID(),
                AuthorizationExpiredIntegrationEvent.EVENT_TYPE,
                AuthorizationExpiredIntegrationEvent.EVENT_VERSION,
                now,
                new AuthorizationExpiredIntegrationEvent(
                        UUID.randomUUID(),
                        "card-123",
                        "100.00",
                        "JPY",
                        now,
                        now
                )
        );
        ConsumerRecord<String, String> record = record(
                objectMapper.writeValueAsString(event),
                event.eventId(),
                event.eventType()
        );

        assertThatThrownBy(() -> parser.parseDecisionEvent(record))
                .isInstanceOf(EventContractException.class)
                .hasMessage("unsupported authorization decision event type authorization.expired");
    }

    private IntegrationEventEnvelope<AuthorizationApprovedIntegrationEvent> approvedEvent(int version) {
        Instant now = Instant.parse("2026-06-14T00:00:00Z");
        return new IntegrationEventEnvelope<>(
                UUID.randomUUID(),
                AuthorizationApprovedIntegrationEvent.EVENT_TYPE,
                version,
                now,
                new AuthorizationApprovedIntegrationEvent(
                        UUID.randomUUID(),
                        "card-123",
                        "100.00",
                        "JPY",
                        now,
                        now
                )
        );
    }

    private IntegrationEventEnvelope<AuthorizationDeclinedIntegrationEvent> declinedEvent(int version) {
        Instant now = Instant.parse("2026-06-14T00:00:00Z");
        return new IntegrationEventEnvelope<>(
                UUID.randomUUID(),
                AuthorizationDeclinedIntegrationEvent.EVENT_TYPE,
                version,
                now,
                new AuthorizationDeclinedIntegrationEvent(
                        UUID.randomUUID(),
                        "card-123",
                        "100.00",
                        "JPY",
                        "INSUFFICIENT_AVAILABLE_CREDIT",
                        now
                )
        );
    }

    private ConsumerRecord<String, String> record(
            String value,
            UUID eventId,
            String eventType
    ) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "mini-card.authorization-events.v1",
                0,
                0,
                "authorization-id",
                value
        );
        record.headers().add(new RecordHeader(
                "eventId",
                eventId.toString().getBytes(StandardCharsets.UTF_8)
        ));
        record.headers().add(new RecordHeader(
                "eventType",
                eventType.getBytes(StandardCharsets.UTF_8)
        ));
        return record;
    }
}
