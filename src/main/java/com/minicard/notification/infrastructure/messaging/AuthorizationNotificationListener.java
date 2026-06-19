package com.minicard.notification.infrastructure.messaging;

import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.minicard.messaging.event.IntegrationEvent;
import com.minicard.messaging.kafka.IntegrationEventReader;
import com.minicard.notification.application.RequestAuthorizationNotificationCommand;
import com.minicard.notification.application.RequestAuthorizationNotificationService;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Notification bounded context 的 Kafka inbound adapter。
 *
 * <p>这里不放通知业务规则，只做 transport contract 转换并调用 application use case。
 * 面试重点：adapter 薄，业务幂等和 transaction boundary 在 service/repository。</p>
 */
@Component
@RequiredArgsConstructor
public class AuthorizationNotificationListener {

    private static final String AUTHORIZATION_APPROVED = "authorization.approved";
    private static final String AUTHORIZATION_DECLINED = "authorization.declined";

    private final IntegrationEventReader eventReader;
    private final RequestAuthorizationNotificationService service;

    @KafkaListener(
            topics = "${messaging.topics.authorization-events}",
            groupId = "${messaging.consumers.notification.group-id}",
            containerFactory = "notificationKafkaListenerContainerFactory"
    )
    public void onAuthorizationDecision(ConsumerRecord<String, String> record) {
        // 先读 eventType，再显式选择 handler。这样新增 authorization.posted 等事件时，
        // Notification 没订阅就直接跳过，不把“合法但不关心”的消息当 contract failure。
        IntegrationEvent event = eventReader.read(record);
        JsonNode payload = event.payload();
        if (AUTHORIZATION_APPROVED.equals(event.eventType())) {
            service.request(new RequestAuthorizationNotificationCommand(
                    event.eventId(),
                    UUID.fromString(eventReader.requiredText(payload, "authorizationId")),
                    eventReader.requiredText(payload, "cardId"),
                    true
            ));
            return;
        }
        if (AUTHORIZATION_DECLINED.equals(event.eventType())) {
            service.request(new RequestAuthorizationNotificationCommand(
                    event.eventId(),
                    UUID.fromString(eventReader.requiredText(payload, "authorizationId")),
                    eventReader.requiredText(payload, "cardId"),
                    false
            ));
        }
    }
}
