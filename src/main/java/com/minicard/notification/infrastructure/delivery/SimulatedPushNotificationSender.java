package com.minicard.notification.infrastructure.delivery;

import com.minicard.notification.domain.delivery.NotificationChannel;
import com.minicard.notification.domain.delivery.NotificationDelivery;
import com.minicard.notification.domain.delivery.NotificationDeliverySender;
import org.springframework.stereotype.Component;

/**
 * APP_PUSH 渠道的 delivery sender。
 *
 * <p>关键词：App 推送, provider sender, retry circuit breaker, push delivery,
 * APNs FCM, プッシュ通知(プッシュつうち)。</p>
 *
 * <p>当前没有设备 token 表，所以 push token 由 recipientKey 合成；真实系统会在本类内替换为
 * device token 查询。它与 EMAIL 使用不同 circuit breaker，provider 故障互不拖累。</p>
 */
@Component
public class SimulatedPushNotificationSender implements NotificationDeliverySender {

    private final NotificationProviderClient notificationProviderClient;
    private final ResilientCallHelper resilientCallHelper;

    public SimulatedPushNotificationSender(
            NotificationProviderClient notificationProviderClient,
            ResilientCallHelper resilientCallHelper
    ) {
        this.notificationProviderClient = notificationProviderClient;
        this.resilientCallHelper = resilientCallHelper;
    }

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.APP_PUSH;
    }

    @Override
    public String send(NotificationDelivery delivery) {
        // 阶段 1：当前 demo 用 recipientKey 合成 device token。未来接真实移动端设备表时，只替换这里。
        String recipientAddress = "push-token-" + delivery.recipientKey();
        // 阶段 2：push 文案保持短；delivery 已经快照 type/subjectId，sender 不回查业务表。
        String title = NotificationMessageTemplates.titleFor(delivery.notificationType());
        String body = NotificationMessageTemplates.bodyFor(
                delivery.notificationType(), delivery.channel(), delivery.subjectId());
        NotificationProviderClient.NotificationProviderRequest request =
                new NotificationProviderClient.NotificationProviderRequest(
                        "push",
                        channel(),
                        recipientAddress,
                        title,
                        body,
                        delivery.idempotencyKey()
                );
        // 阶段 3：通过 Feign 走真实 HTTP/JSON 边界，并套 Retry + CircuitBreaker；push/email 的 breaker name 分开。
        return resilientCallHelper.call(
                "notificationPush",
                () -> notificationProviderClient.send(request).providerMessageId()
        );
    }
}
