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
    private final RequestNotificationService service;

    // @KafkaListener 的 groupId 复用 notification 组，表示所有通知 listener 共享“通知上下文”的消费进度。
    // containerFactory 指向 notification 专用 retry/DLT；如果误用 ledger/risk factory，失败消息会进错 DLT。
    @KafkaListener(
            topics = "${messaging.topics.transaction-events}",
            groupId = "${messaging.consumers.notification.group-id}",
            containerFactory = "notificationKafkaListenerContainerFactory"
    )
    public void onCardTransactionEvent(ConsumerRecord<String, String> record) {
        // 只处理用户可见交易入账事件；未来 refunded/disputed 可以在这里继续显式增加分支。
        // 如果把 authorization.posted 也当成交易通知，会把 issuer 内部 hold 生命周期误发给用户。
        IntegrationEvent event = eventReader.read(record);
        if (CARD_TRANSACTION_POSTED.equals(event.eventType())) {
            // 先判断 eventType 再解析 payload；合法但无关的事件不应该因为缺字段进入 DLT。
            JsonNode payload = event.payload();
            service.request(new RequestNotificationCommand(
                    event.eventId(),
                    NotificationSubjectType.CARD_TRANSACTION,
                    eventReader.requiredText(payload, "cardTransactionId"),
                    eventReader.requiredText(payload, "cardId"),
                    NotificationType.CARD_TRANSACTION_POSTED
            ));
        }
    }
}
