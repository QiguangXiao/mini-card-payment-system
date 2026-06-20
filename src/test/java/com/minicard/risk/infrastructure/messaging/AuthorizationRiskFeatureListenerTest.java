package com.minicard.risk.infrastructure.messaging;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.minicard.messaging.event.IntegrationEvent;
import com.minicard.messaging.kafka.IntegrationEventReader;
import com.minicard.risk.application.AuthorizationDecisionOutcome;
import com.minicard.risk.application.ProjectRiskFeatureCommand;
import com.minicard.risk.application.RiskFeatureProjectionService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class AuthorizationRiskFeatureListenerTest {

    private static final Instant NOW = Instant.parse("2026-06-20T00:00:00Z");

    private final ObjectMapper objectMapper =
            new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void approvedEventProjectsApprovedRiskFeature() throws Exception {
        RiskFeatureProjectionService service = mock(RiskFeatureProjectionService.class);
        AuthorizationRiskFeatureListener listener = listener(service);
        UUID eventId = UUID.randomUUID();

        listener.onAuthorizationDecision(record(
                eventId,
                "authorization.approved",
                payload("approvedAt")
        ));

        ArgumentCaptor<ProjectRiskFeatureCommand> command =
                ArgumentCaptor.forClass(ProjectRiskFeatureCommand.class);
        verify(service).project(command.capture());
        assertThat(command.getValue().sourceEventId()).isEqualTo(eventId);
        assertThat(command.getValue().cardId()).isEqualTo("card-123");
        assertThat(command.getValue().outcome()).isEqualTo(AuthorizationDecisionOutcome.APPROVED);
        assertThat(command.getValue().decidedAt()).isEqualTo(NOW);
    }

    @Test
    void authorizationPostedEventIsSkipped() throws Exception {
        RiskFeatureProjectionService service = mock(RiskFeatureProjectionService.class);
        AuthorizationRiskFeatureListener listener = listener(service);

        listener.onAuthorizationDecision(record(
                UUID.randomUUID(),
                "authorization.posted",
                payload("postedAt")
        ));

        verifyNoInteractions(service);
    }

    private AuthorizationRiskFeatureListener listener(RiskFeatureProjectionService service) {
        return new AuthorizationRiskFeatureListener(
                new IntegrationEventReader(objectMapper),
                service
        );
    }

    private ObjectNode payload(String timeField) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("authorizationId", UUID.randomUUID().toString());
        payload.put("cardId", "card-123");
        payload.put("amount", "100.00");
        payload.put("currency", "JPY");
        payload.put(timeField, NOW.toString());
        return payload;
    }

    private ConsumerRecord<String, String> record(
            UUID eventId,
            String eventType,
            ObjectNode payload
    ) throws Exception {
        IntegrationEvent event = new IntegrationEvent(
                eventId,
                eventType,
                1,
                NOW,
                payload
        );
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "mini-card.authorization-events.v1",
                0,
                0,
                payload.get("authorizationId").asText(),
                objectMapper.writeValueAsString(event)
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
