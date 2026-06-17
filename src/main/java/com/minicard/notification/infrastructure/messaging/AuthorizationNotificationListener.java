package com.minicard.notification.infrastructure.messaging;

import com.minicard.messaging.contract.authorization.AuthorizationApprovedPayload;
import com.minicard.messaging.contract.authorization.AuthorizationDeclinedPayload;
import com.minicard.messaging.kafka.IntegrationEventReader;
import com.minicard.notification.application.RequestAuthorizationNotificationCommand;
import com.minicard.notification.application.RequestAuthorizationNotificationService;
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
public class AuthorizationNotificationListener {

    private final IntegrationEventReader eventReader;
    private final RequestAuthorizationNotificationService service;

    public AuthorizationNotificationListener(
            IntegrationEventReader eventReader,
            RequestAuthorizationNotificationService service
    ) {
        this.eventReader = eventReader;
        this.service = service;
    }

    @KafkaListener(
            topics = "${messaging.topics.authorization-events}",
            groupId = "${messaging.consumers.notification.group-id}",
            containerFactory = "notificationKafkaListenerContainerFactory"
    )
    public void onAuthorizationDecision(ConsumerRecord<String, String> record) {
        // 先读 eventType，再显式选择 handler。这样新增 authorization.captured 等事件时，
        // Notification 没订阅就直接跳过，不把“合法但不关心”的消息当 contract failure。
        String eventType = eventReader.eventType(record);
        if (AuthorizationApprovedPayload.EVENT_TYPE.equals(eventType)) {
            var event = eventReader.read(
                    record,
                    AuthorizationApprovedPayload.class,
                    AuthorizationApprovedPayload.EVENT_TYPE,
                    AuthorizationApprovedPayload.EVENT_VERSION
            );
            var payload = event.payload();
            service.request(new RequestAuthorizationNotificationCommand(
                    event.eventId(),
                    payload.authorizationId(),
                    payload.cardId(),
                    true
            ));
            return;
        }
        if (AuthorizationDeclinedPayload.EVENT_TYPE.equals(eventType)) {
            var event = eventReader.read(
                    record,
                    AuthorizationDeclinedPayload.class,
                    AuthorizationDeclinedPayload.EVENT_TYPE,
                    AuthorizationDeclinedPayload.EVENT_VERSION
            );
            var payload = event.payload();
            service.request(new RequestAuthorizationNotificationCommand(
                    event.eventId(),
                    payload.authorizationId(),
                    payload.cardId(),
                    false
            ));
        }
    }
}
