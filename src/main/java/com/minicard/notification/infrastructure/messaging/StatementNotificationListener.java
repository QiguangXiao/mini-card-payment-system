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
 * <p>账单生成通知从 statement.closed 触发，而不是由 StatementGenerationService 直接调用通知服务。
 * 这样账单生成主事务只依赖本地 MySQL，通知失败由 Kafka retry/DLT 处理。
 * 它和 Authorization/CardTransaction/Repayment 三个 listener 完全同构：同一个 notification 消费组、
 * 同一个 containerFactory、同一套 Inbox 幂等。</p>
 */
@Component
@RequiredArgsConstructor
public class StatementNotificationListener {

    private static final String STATEMENT_CLOSED = "statement.closed";

    private final IntegrationEventReader eventReader;
    private final RequestNotificationService service;

    // topics/groupId/containerFactory 都用配置占位符，而不是硬编码字符串。
    // groupId 复用 notification 组，表示所有通知 listener 共享“通知上下文”的消费进度；
    // containerFactory 指向 notification 专用 retry/DLT，如果误用 ledger/risk factory，失败消息会进错 DLT。
    @KafkaListener(
            topics = "${messaging.topics.statement-events}",
            groupId = "${messaging.consumers.notification.group-id}",
            containerFactory = "notificationKafkaListenerContainerFactory"
    )
    public void onStatementEvent(ConsumerRecord<String, String> record) {
        IntegrationEvent event = eventReader.read(record);
        if (!STATEMENT_CLOSED.equals(event.eventType())) {
            // 同 topic 未来可能出现 statement.overdue 等事件；不关心时跳过，不进入 retry/DLT。
            return;
        }
        JsonNode payload = event.payload();
        // 当前项目没有 customer/cardholder 表，statement payload 也没有用户 id。
        // 暂时使用 creditAccountId 作为 recipientKey，后续接用户模型时应改成 customerId。
        service.request(new RequestNotificationCommand(
                event.eventId(),
                NotificationSubjectType.STATEMENT,
                eventReader.requiredText(payload, "statementId"),
                eventReader.requiredText(payload, "creditAccountId"),
                NotificationType.STATEMENT_CLOSED
        ));
    }
}
