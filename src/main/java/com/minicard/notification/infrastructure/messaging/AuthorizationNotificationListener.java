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
        // @KafkaListener 的 groupId 决定“哪一组消费者共享进度”；containerFactory 决定 retry/DLT 策略。
        // 如果所有 bounded context 共用一个 group，Notification 可能抢走 Risk/Ledger 应该处理的消息。
        // Authorization listener 只处理授权决策通知。authorization.posted 属于授权生命周期，
        // 不代表“用户可见交易已入账”，所以不会在这里创建 posted 通知。
        // offset commit 也不是这里手写的：application.yml 关闭 Kafka 原生 auto commit，
        // 但设置 ack-mode=record，让 Spring Kafka container 在本方法正常 return 后自动提交这条 record 的 offset。
        // 如果 read()/requestNotification() 抛异常，异常会交给 notificationKafkaListenerContainerFactory
        // 配置的 DefaultErrorHandler：先按策略 retry，仍失败再投递到 notification DLT。
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
