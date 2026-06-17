package com.minicard.messaging.kafka;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.minicard.messaging.contract.authorization.AuthorizationApprovedPayload;
import com.minicard.messaging.contract.authorization.AuthorizationExpiredPayload;
import com.minicard.messaging.event.IntegrationEventEnvelope;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IntegrationEventReaderTest {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final IntegrationEventReader reader = new IntegrationEventReader(objectMapper);

    @Test
    void readsEventTypeBeforeConsumerChoosesHandler() throws Exception {
        IntegrationEventEnvelope<AuthorizationExpiredPayload> event = expiredEvent();
        ConsumerRecord<String, String> record = record(
                objectMapper.writeValueAsString(event),
                event.eventId(),
                event.eventType()
        );

        assertThat(reader.eventType(record)).isEqualTo(AuthorizationExpiredPayload.EVENT_TYPE);
    }

    @Test
    void readsTypedPayloadWithMatchingHeaders() throws Exception {
        IntegrationEventEnvelope<AuthorizationApprovedPayload> event = approvedEvent(1);
        ConsumerRecord<String, String> record = record(
                objectMapper.writeValueAsString(event),
                event.eventId(),
                event.eventType()
        );

        IntegrationEventEnvelope<AuthorizationApprovedPayload> parsed = reader.read(
                record,
                AuthorizationApprovedPayload.class,
                AuthorizationApprovedPayload.EVENT_TYPE,
                AuthorizationApprovedPayload.EVENT_VERSION
        );

        assertThat(parsed.eventId()).isEqualTo(event.eventId());
        assertThat(parsed.payload().authorizationId()).isEqualTo(event.payload().authorizationId());
    }

    @Test
    void rejectsUnsupportedVersionAsPermanentContractFailure() throws Exception {
        IntegrationEventEnvelope<AuthorizationApprovedPayload> event = approvedEvent(99);
        ConsumerRecord<String, String> record = record(
                objectMapper.writeValueAsString(event),
                event.eventId(),
                event.eventType()
        );

        assertThatThrownBy(() -> reader.read(
                record,
                AuthorizationApprovedPayload.class,
                AuthorizationApprovedPayload.EVENT_TYPE,
                AuthorizationApprovedPayload.EVENT_VERSION
        ))
                .isInstanceOf(EventContractException.class)
                .hasMessage("unsupported event version 99");
    }

    @Test
    void rejectsWrongTypedReaderForAnotherEvent() throws Exception {
        IntegrationEventEnvelope<AuthorizationExpiredPayload> event = expiredEvent();
        ConsumerRecord<String, String> record = record(
                objectMapper.writeValueAsString(event),
                event.eventId(),
                event.eventType()
        );

        assertThatThrownBy(() -> reader.read(
                record,
                AuthorizationApprovedPayload.class,
                AuthorizationApprovedPayload.EVENT_TYPE,
                AuthorizationApprovedPayload.EVENT_VERSION
        ))
                .isInstanceOf(EventContractException.class)
                .hasMessage("unsupported event type authorization.expired");
    }

    private IntegrationEventEnvelope<AuthorizationApprovedPayload> approvedEvent(int version) {
        Instant now = Instant.parse("2026-06-14T00:00:00Z");
        return new IntegrationEventEnvelope<>(
                UUID.randomUUID(),
                AuthorizationApprovedPayload.EVENT_TYPE,
                version,
                now,
                new AuthorizationApprovedPayload(
                        UUID.randomUUID(),
                        "card-123",
                        "100.00",
                        "JPY",
                        now,
                        now
                )
        );
    }

    private IntegrationEventEnvelope<AuthorizationExpiredPayload> expiredEvent() {
        Instant now = Instant.parse("2026-06-14T00:00:00Z");
        return new IntegrationEventEnvelope<>(
                UUID.randomUUID(),
                AuthorizationExpiredPayload.EVENT_TYPE,
                AuthorizationExpiredPayload.EVENT_VERSION,
                now,
                new AuthorizationExpiredPayload(
                        UUID.randomUUID(),
                        "card-123",
                        "100.00",
                        "JPY",
                        now,
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
