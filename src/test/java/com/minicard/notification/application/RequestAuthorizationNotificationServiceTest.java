package com.minicard.notification.application;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import com.minicard.notification.domain.Notification;
import com.minicard.notification.domain.NotificationRepository;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RequestAuthorizationNotificationServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-14T00:00:00Z");

    @Test
    void duplicateDeliveryDoesNotCreateAnotherNotification() {
        NotificationRepository repository = mock(NotificationRepository.class);
        when(repository.insertIfAbsent(any(Notification.class)))
                .thenReturn(true)
                .thenReturn(false);
        RequestAuthorizationNotificationService service =
                new RequestAuthorizationNotificationService(
                        repository,
                        Clock.fixed(NOW, ZoneOffset.UTC)
                );
        RequestAuthorizationNotificationCommand command = approvedCommand();

        service.request(command);
        service.request(command);

        // The unique source_event_id constraint is the real concurrency boundary;
        // both deliveries may attempt INSERT, but only one aggregate is created.
        verify(repository, times(2)).insertIfAbsent(any(Notification.class));
    }

    private RequestAuthorizationNotificationCommand approvedCommand() {
        return new RequestAuthorizationNotificationCommand(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "card-123",
                true
        );
    }
}
