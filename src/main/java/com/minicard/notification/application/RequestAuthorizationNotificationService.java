package com.minicard.notification.application;

import java.time.Clock;
import java.time.Instant;

import com.minicard.notification.domain.Notification;
import com.minicard.notification.domain.NotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application use case that requests a notification from an authorization event.
 *
 * <p>The use case is intentionally named after the business action rather than
 * Kafka consumption. Kafka is only one way this use case can be invoked.</p>
 */
@Service
public class RequestAuthorizationNotificationService {

    private static final Logger log =
            LoggerFactory.getLogger(RequestAuthorizationNotificationService.class);

    private final NotificationRepository notificationRepository;
    private final Clock clock;

    public RequestAuthorizationNotificationService(
            NotificationRepository notificationRepository,
            Clock clock
    ) {
        this.notificationRepository = notificationRepository;
        this.clock = clock;
    }

    @Transactional
    public void request(RequestAuthorizationNotificationCommand command) {
        Notification notification = Notification.requestAuthorizationDecision(
                command.sourceEventId(),
                command.authorizationId(),
                command.cardId(),
                command.approved(),
                Instant.now(clock)
        );
        if (!notificationRepository.insertIfAbsent(notification)) {
            // Returning successfully acknowledges a duplicate event. The unique
            // source_event_id constraint protects concurrent application nodes.
            log.info("notification_request_duplicate eventId={}", command.sourceEventId());
            return;
        }
        log.info(
                "notification_requested eventId={} notificationId={} template={}",
                command.sourceEventId(),
                notification.id(),
                notification.template()
        );
    }
}
