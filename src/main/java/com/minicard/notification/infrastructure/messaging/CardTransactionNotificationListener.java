package com.minicard.notification.infrastructure.messaging;

import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.minicard.messaging.event.IntegrationEvent;
import com.minicard.messaging.kafka.IntegrationEventReader;
import com.minicard.notification.application.RequestAuthorizationNotificationCommand;
import com.minicard.notification.application.RequestAuthorizationNotificationService;
import com.minicard.notification.domain.NotificationType;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * CardTransaction 事件进入 Notification bounded context 的 Kafka inbound adapter。
 *
 * <p>CardTransaction 是用户可见交易流水。posting 成功通知从这里触发，
 * 避免 Notification 依赖 authorization 内部生命周期事件。</p>
 */
@Component
@RequiredArgsConstructor
public class CardTransactionNotificationListener {

    private static final String CARD_TRANSACTION_POSTED = "card_transaction.posted";

    private final IntegrationEventReader eventReader;
    private final RequestAuthorizationNotificationService service;

    @KafkaListener(
            topics = "${messaging.topics.transaction-events}",
            groupId = "${messaging.consumers.notification.group-id}",
            containerFactory = "notificationKafkaListenerContainerFactory"
    )
    public void onCardTransactionEvent(ConsumerRecord<String, String> record) {
        // 只处理用户可见交易入账事件；未来 refunded/disputed 可以在这里继续显式增加分支。
        IntegrationEvent event = eventReader.read(record);
        if (CARD_TRANSACTION_POSTED.equals(event.eventType())) {
            JsonNode payload = event.payload();
            service.request(new RequestAuthorizationNotificationCommand(
                    event.eventId(),
                    UUID.fromString(eventReader.requiredText(payload, "authorizationId")),
                    eventReader.requiredText(payload, "cardId"),
                    NotificationType.CARD_TRANSACTION_POSTED
            ));
        }
    }
}
