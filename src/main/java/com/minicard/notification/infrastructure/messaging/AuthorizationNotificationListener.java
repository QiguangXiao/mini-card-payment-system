package com.minicard.notification.infrastructure.messaging;

import com.minicard.messaging.kafka.AuthorizationEventParser;
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

    private final AuthorizationEventParser eventParser;
    private final RequestAuthorizationNotificationService service;

    public AuthorizationNotificationListener(
            AuthorizationEventParser eventParser,
            RequestAuthorizationNotificationService service
    ) {
        this.eventParser = eventParser;
        this.service = service;
    }

    @KafkaListener(
            topics = "${messaging.topics.authorization-events}",
            groupId = "${messaging.consumers.notification.group-id}",
            containerFactory = "notificationKafkaListenerContainerFactory"
    )
    public void onAuthorizationDecided(ConsumerRecord<String, String> record) {
        // parse() 统一做 event contract validation；listener 只关心如何把事件映射成 command。
        var event = eventParser.parse(record);
        service.request(new RequestAuthorizationNotificationCommand(
                event.eventId(),
                event.payload().authorizationId(),
                event.payload().cardId(),
                "APPROVED".equals(event.payload().status())
        ));
    }
}
