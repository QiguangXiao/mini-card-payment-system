package com.minicard.notification.application;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import com.minicard.messaging.inbox.ConsumerInboxRepository;
import com.minicard.notification.domain.Notification;
import com.minicard.notification.domain.NotificationRepository;
import com.minicard.notification.domain.NotificationType;
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
        ConsumerInboxRepository inboxRepository = mock(ConsumerInboxRepository.class);
        NotificationRepository repository = mock(NotificationRepository.class);
        when(inboxRepository.claim(any(), any(), any()))
                .thenReturn(true)
                .thenReturn(false);
        when(repository.insertIfAbsent(any(Notification.class)))
                .thenReturn(true);
        RequestAuthorizationNotificationService service =
                new RequestAuthorizationNotificationService(
                        inboxRepository,
                        repository,
                        Clock.fixed(NOW, ZoneOffset.UTC)
                );
        RequestAuthorizationNotificationCommand command = approvedCommand();

        service.request(command);
        service.request(command);

        // Inbox claim 是第一道 consumer-side idempotency 边界；
        // duplicate delivery 不会继续创建第二条 notification request。
        verify(inboxRepository, times(2)).claim(any(), any(), any());
        verify(repository, times(1)).insertIfAbsent(any(Notification.class));
    }

    private RequestAuthorizationNotificationCommand approvedCommand() {
        return new RequestAuthorizationNotificationCommand(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "card-123",
                NotificationType.AUTHORIZATION_APPROVED
        );
    }
}
