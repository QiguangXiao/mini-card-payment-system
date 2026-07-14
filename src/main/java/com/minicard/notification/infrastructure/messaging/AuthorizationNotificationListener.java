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
 *
 * <p>阅读顺序：{@code @KafkaListener} 负责把 record 送进方法；{@link IntegrationEventReader}
 * 负责 transport contract；本 adapter 按 eventType 路由并把 payload 翻译成 command；
 * {@link RequestNotificationService} 才负责 Inbox idempotency 和创建通知投递记录。</p>
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
            concurrency = "${messaging.consumers.notification.concurrency}"
    )
    public void onAuthorizationEvent(ConsumerRecord<String, String> record) {
        // 阶段 1：@KafkaListener 的 topics 决定从哪里读，groupId 决定和谁共享消费进度，
        // concurrency 决定本 container 的并行线程数（有效上限是 topic partition 数）。
        // group 是 Notification bounded context 的持久消费身份，多实例共享它才会竞争同一份进度。
        // Authorization listener 只处理授权决策通知。authorization.posted 属于授权生命周期，
        // 不代表“用户可见交易已入账”，所以不会在这里创建 posted 通知。
        // offset commit 也不是这里手写的：application.yml 关闭 Kafka 原生 auto commit，
        // 但设置 ack-mode=record，让 Spring Kafka container 在本方法正常 return 后自动提交这条 record 的 offset。
        // 如果 read()/requestNotification() 抛异常，异常会交给默认 listener factory 上的全局
        // DefaultErrorHandler：先按策略 retry，仍失败再按本 listener 的 groupId 路由到 notification DLT
        //（KafkaConsumerConfiguration）。
        // 阶段 2：先解析并校验公共 envelope。坏 JSON/缺 eventId 会作为 contract failure 直接进入 DLT。
        IntegrationEvent event = eventReader.read(record);
        JsonNode payload = event.payload();
        // 阶段 3：只把本 bounded context 感兴趣的 eventType 翻译成 notification command。
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
        // 阶段 4：进入 application service。eventId 会成为 Inbox 幂等键；Kafka retry/重复投递
        // 不会重复创建同一批 notification_deliveries。
        service.requestNotification(new RequestNotificationCommand(
                event.eventId(),
                NotificationSubjectType.AUTHORIZATION,
                eventReader.requiredText(payload, "authorizationId"),
                eventReader.requiredText(payload, "cardId"),
                type
        ));
    }
}
