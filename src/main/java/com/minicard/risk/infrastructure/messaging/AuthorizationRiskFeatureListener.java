package com.minicard.risk.infrastructure.messaging;

import com.minicard.messaging.kafka.AuthorizationEventParser;
import com.minicard.risk.application.projection.AuthorizationRiskFeatureProjectionService;
import com.minicard.risk.application.projection.RecordAuthorizationDecisionCommand;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Kafka inbound adapter that feeds authorization history into the Risk context.
 */
@Component
public class AuthorizationRiskFeatureListener {

    private final AuthorizationEventParser eventParser;
    private final AuthorizationRiskFeatureProjectionService projectionService;

    public AuthorizationRiskFeatureListener(
            AuthorizationEventParser eventParser,
            AuthorizationRiskFeatureProjectionService projectionService
    ) {
        this.eventParser = eventParser;
        this.projectionService = projectionService;
    }

    @KafkaListener(
            topics = "${messaging.topics.authorization-events}",
            groupId = "${messaging.consumers.risk-feature.group-id}",
            containerFactory = "riskFeatureKafkaListenerContainerFactory"
    )
    public void onAuthorizationDecided(ConsumerRecord<String, String> record) {
        // This adapter only translates transport input. Idempotency and the
        // transaction boundary belong to the projection application service.
        var event = eventParser.parse(record);
        projectionService.project(new RecordAuthorizationDecisionCommand(
                event.eventId(),
                event.payload().cardId(),
                event.payload().status(),
                event.payload().decidedAt()
        ));
    }
}
