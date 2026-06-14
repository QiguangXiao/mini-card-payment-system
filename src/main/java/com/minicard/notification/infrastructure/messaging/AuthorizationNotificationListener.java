package com.minicard.notification.infrastructure.messaging;

import com.minicard.messaging.kafka.AuthorizationEventParser;
import com.minicard.notification.application.RequestAuthorizationNotificationCommand;
import com.minicard.notification.application.RequestAuthorizationNotificationService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Kafka inbound adapter for the Notification bounded context.
 *
 * <p>It contains no notification business rules: it translates the transport
 * contract and invokes the application use case.</p>
 */
@Component
public class AuthorizationNotificationListener {

    private final AuthorizationEventParser eventParser;
    private final RequestAuthorizationNotificationService service;

    public AuthorizationNotificationListener(
            AuthorizationEventParser eventParser,
            RequestAuthorizationNotificationService service
    ) {
        this.eventParser = eventParser;
        this.service = service;
    }

    @KafkaListener(
            topics = "${messaging.topics.authorization-events}",
            groupId = "${messaging.consumers.notification.group-id}",
            containerFactory = "notificationKafkaListenerContainerFactory"
    )
    public void onAuthorizationDecided(ConsumerRecord<String, String> record) {
        var event = eventParser.parse(record);
        service.request(new RequestAuthorizationNotificationCommand(
                event.eventId(),
                event.payload().authorizationId(),
                event.payload().cardId(),
                "APPROVED".equals(event.payload().status())
        ));
    }
}
