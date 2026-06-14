package com.minicard.messaging.kafka;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.minicard.messaging.event.AuthorizationDecidedEvent;
import com.minicard.messaging.event.IntegrationEventEnvelope;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthorizationEventParserTest {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final AuthorizationEventParser parser = new AuthorizationEventParser(objectMapper);

    @Test
    void parsesSupportedVersionWithMatchingEventIdHeader() throws Exception {
        IntegrationEventEnvelope<AuthorizationDecidedEvent> event = event(1);
        ConsumerRecord<String, String> record = record(
                objectMapper.writeValueAsString(event),
                event.eventId()
        );

        assertThat(parser.parse(record)).isEqualTo(event);
    }

    @Test
    void rejectsUnsupportedVersionAsPermanentContractFailure() throws Exception {
        IntegrationEventEnvelope<AuthorizationDecidedEvent> event = event(99);
        ConsumerRecord<String, String> record = record(
                objectMapper.writeValueAsString(event),
                event.eventId()
        );

        assertThatThrownBy(() -> parser.parse(record))
                .isInstanceOf(EventContractException.class)
                .hasMessage("unsupported authorization event version 99");
    }

    private IntegrationEventEnvelope<AuthorizationDecidedEvent> event(int version) {
        Instant now = Instant.parse("2026-06-14T00:00:00Z");
        return new IntegrationEventEnvelope<>(
                UUID.randomUUID(),
                AuthorizationDecidedEvent.EVENT_TYPE,
                version,
                now,
                new AuthorizationDecidedEvent(
                        UUID.randomUUID(),
                        "card-123",
                        "100.00",
                        "JPY",
                        "APPROVED",
                        null,
                        now
                )
        );
    }

    private ConsumerRecord<String, String> record(String value, UUID eventId) {
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
        return record;
    }
}
