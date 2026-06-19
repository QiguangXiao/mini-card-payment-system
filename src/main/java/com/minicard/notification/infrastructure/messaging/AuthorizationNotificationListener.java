package com.minicard.notification.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.minicard.messaging.event.IntegrationEvent;
import com.minicard.messaging.kafka.IntegrationEventReader;
import com.minicard.notification.application.RequestNotificationCommand;
import com.minicard.notification.application.RequestNotificationService;
import com.minicard.notification.domain.NotificationSubjectType;
import com.minicard.notification.domain.NotificationType;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Authorization 事件进入 Notification bounded context 的 Kafka inbound adapter。
 *
 * <p>未来 Notification 拆成独立微服务时，它依然只依赖 Kafka integration event contract，
 * 不依赖 authorization domain class。这里不做通知业务规则，只把授权事件翻译成通知请求。</p>
 */
@Component
@RequiredArgsConstructor
public class AuthorizationNotificationListener {

    private static final String AUTHORIZATION_APPROVED = "authorization.approved";
    private static final String AUTHORIZATION_DECLINED = "authorization.declined";

    private final IntegrationEventReader eventReader;
    private final RequestNotificationService service;

    @KafkaListener(
            topics = "${messaging.topics.authorization-events}",
            groupId = "${messaging.consumers.notification.group-id}",
            containerFactory = "notificationKafkaListenerContainerFactory"
    )
    public void onAuthorizationEvent(ConsumerRecord<String, String> record) {
        // Authorization listener 只处理授权决策通知。authorization.posted 属于授权生命周期，
        // 不代表“用户可见交易已入账”，所以不会在这里创建 posted 通知。
        IntegrationEvent event = eventReader.read(record);
        JsonNode payload = event.payload();
        if (AUTHORIZATION_APPROVED.equals(event.eventType())) {
            requestNotification(event, payload, NotificationType.AUTHORIZATION_APPROVED);
            return;
        }
        if (AUTHORIZATION_DECLINED.equals(event.eventType())) {
            requestNotification(event, payload, NotificationType.AUTHORIZATION_DECLINED);
        }
    }

    private void requestNotification(
            IntegrationEvent event,
            JsonNode payload,
            NotificationType type
    ) {
        service.request(new RequestNotificationCommand(
                event.eventId(),
                NotificationSubjectType.AUTHORIZATION,
                eventReader.requiredText(payload, "authorizationId"),
                eventReader.requiredText(payload, "cardId"),
                type
        ));
    }
}
