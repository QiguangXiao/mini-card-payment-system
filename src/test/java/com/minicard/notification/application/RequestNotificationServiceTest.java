package com.minicard.notification.application;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.minicard.messaging.inbox.ConsumerInboxRepository;
import com.minicard.notification.domain.Notification;
import com.minicard.notification.domain.NotificationRepository;
import com.minicard.notification.domain.NotificationSubjectType;
import com.minicard.notification.domain.NotificationType;
import com.minicard.notification.domain.delivery.NotificationChannel;
import com.minicard.notification.domain.delivery.NotificationDelivery;
import com.minicard.notification.domain.delivery.NotificationDeliveryRepository;
import com.minicard.notification.domain.delivery.NotificationDeliveryStatus;
import com.minicard.notification.domain.delivery.NotificationRecipient;
import com.minicard.notification.domain.delivery.NotificationRecipientResolver;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RequestNotificationServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-14T00:00:00Z");

    private final ConsumerInboxRepository inboxRepository = mock(ConsumerInboxRepository.class);
    private final NotificationRepository notificationRepository = mock(NotificationRepository.class);
    private final NotificationDeliveryRepository deliveryRepository = mock(NotificationDeliveryRepository.class);
    private final NotificationRecipientResolver recipientResolver = mock(NotificationRecipientResolver.class);

    private final RequestNotificationService service = new RequestNotificationService(
            inboxRepository,
            notificationRepository,
            deliveryRepository,
            recipientResolver,
            Clock.fixed(NOW, ZoneOffset.UTC)
    );

    @Test
    void fansOutOneDeliveryPerEnabledChannel() {
        when(inboxRepository.claim(any(), any(), any())).thenReturn(true);
        when(notificationRepository.insertIfAbsent(any(Notification.class))).thenReturn(true);
        when(recipientResolver.resolve("card-123")).thenReturn(new NotificationRecipient(
                "card-123",
                Map.of(
                        NotificationChannel.APP_PUSH, "push-token-card-123",
                        NotificationChannel.EMAIL, "user-card-123@example.com"
                )
        ));

        service.request(approvedCommand());

        ArgumentCaptor<List<NotificationDelivery>> captor = ArgumentCaptor.captor();
        verify(deliveryRepository).insertAll(captor.capture());
        List<NotificationDelivery> deliveries = captor.getValue();
        // 一条通知按收件人启用渠道扇出成两条独立 PENDING 投递。
        assertThat(deliveries).hasSize(2);
        assertThat(deliveries).allMatch(d -> d.status() == NotificationDeliveryStatus.PENDING);
        assertThat(deliveries).extracting(NotificationDelivery::channel)
                .containsExactlyInAnyOrder(NotificationChannel.APP_PUSH, NotificationChannel.EMAIL);
    }

    @Test
    void duplicateDeliveryDoesNotCreateAnotherNotificationOrDeliveries() {
        when(inboxRepository.claim(any(), any(), any()))
                .thenReturn(true)
                .thenReturn(false);
        when(notificationRepository.insertIfAbsent(any(Notification.class))).thenReturn(true);
        when(recipientResolver.resolve(any())).thenReturn(new NotificationRecipient(
                "card-123",
                Map.of(NotificationChannel.APP_PUSH, "push-token-card-123")
        ));
        RequestNotificationCommand command = approvedCommand();

        service.request(command);
        service.request(command);

        // Inbox claim 是第一道幂等边界；重复投递不会再创建通知，也不会再创建投递。
        verify(inboxRepository, times(2)).claim(any(), any(), any());
        verify(notificationRepository, times(1)).insertIfAbsent(any(Notification.class));
        verify(deliveryRepository, times(1)).insertAll(any());
    }

    @Test
    void notificationInsertConflictSkipsDeliveryCreation() {
        when(inboxRepository.claim(any(), any(), any())).thenReturn(true);
        // source_event_id 唯一键冲突：第二道幂等保护命中，连带不创建投递。
        when(notificationRepository.insertIfAbsent(any(Notification.class))).thenReturn(false);

        service.request(approvedCommand());

        verify(deliveryRepository, never()).insertAll(any());
    }

    private RequestNotificationCommand approvedCommand() {
        return new RequestNotificationCommand(
                UUID.randomUUID(),
                NotificationSubjectType.AUTHORIZATION,
                UUID.randomUUID().toString(),
                "card-123",
                NotificationType.AUTHORIZATION_APPROVED
        );
    }
}
