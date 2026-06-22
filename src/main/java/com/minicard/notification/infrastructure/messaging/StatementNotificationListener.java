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
 * Statement 事件进入 Notification bounded context 的 Kafka inbound adapter。
 *
 * <p>账单生成通知必须从 statement.closed 事件触发，而不是由 StatementService 直接调用通知服务。
 * 这样账单生成的 transaction boundary 不会被 push/email provider 的失败扩大。</p>
 */
@Component
@RequiredArgsConstructor
public class StatementNotificationListener {

    private static final String STATEMENT_CLOSED = "statement.closed";

    private final IntegrationEventReader eventReader;
    private final RequestNotificationService service;

    // 同一个 notification group 可以消费多个 topic；每个 listener 仍只处理自己关心的 eventType。
    // 如果不先过滤 eventType，合法但无关的 statement 事件可能因为缺字段被错误送进 DLT。
    @KafkaListener(
            topics = "${messaging.topics.statement-events}",
            groupId = "${messaging.consumers.notification.group-id}",
            containerFactory = "notificationKafkaListenerContainerFactory"
    )
    public void onStatementEvent(ConsumerRecord<String, String> record) {
        IntegrationEvent event = eventReader.read(record);
        if (!STATEMENT_CLOSED.equals(event.eventType())) {
            // 不关心的合法事件直接跳过；不要先读 statementId，否则会把 schema 正常不同的事件误判为坏消息。
            return;
        }
        JsonNode payload = event.payload();
        // 警示：当前项目还没有 customer/cardholder 表，statement payload 也没有 cardId。
        // 所以这里暂时使用 creditAccountId 作为 recipientKey；生产系统应在这里查找持卡人通知偏好。
        service.request(new RequestNotificationCommand(
                event.eventId(),
                NotificationSubjectType.STATEMENT,
                eventReader.requiredText(payload, "statementId"),
                eventReader.requiredText(payload, "creditAccountId"),
                NotificationType.STATEMENT_READY
        ));
    }
}
