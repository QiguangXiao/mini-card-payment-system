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
 * Repayment 事件进入 Notification bounded context 的 Kafka inbound adapter。
 *
 * <p>还款成功通知从 repayment.received 触发，而不是由 RepaymentService 直接调用通知服务。
 * 这样还款主事务只依赖本地 MySQL，通知失败由 Kafka retry/DLT 处理。</p>
 */
@Component
@RequiredArgsConstructor
public class RepaymentNotificationListener {

    private static final String REPAYMENT_RECEIVED = "repayment.received";

    private final IntegrationEventReader eventReader;
    private final RequestNotificationService service;

    @KafkaListener(
            topics = "${messaging.topics.repayment-events}",
            groupId = "${messaging.consumers.notification.group-id}",
            containerFactory = "notificationKafkaListenerContainerFactory"
    )
    public void onRepaymentEvent(ConsumerRecord<String, String> record) {
        IntegrationEvent event = eventReader.read(record);
        if (!REPAYMENT_RECEIVED.equals(event.eventType())) {
            return;
        }
        JsonNode payload = event.payload();
        // 当前项目没有 customer/cardholder 表，repayment payload 也没有用户 ID。
        // 暂时使用 creditAccountId 作为 recipientKey，后续接用户模型时应改成 customerId。
        service.request(new RequestNotificationCommand(
                event.eventId(),
                NotificationSubjectType.REPAYMENT,
                eventReader.requiredText(payload, "repaymentId"),
                eventReader.requiredText(payload, "creditAccountId"),
                NotificationType.REPAYMENT_RECEIVED
        ));
    }
}
