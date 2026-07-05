package com.minicard.notification.infrastructure.delivery;

import com.minicard.notification.domain.delivery.NotificationChannel;
import com.minicard.notification.domain.delivery.NotificationDelivery;
import com.minicard.notification.domain.delivery.NotificationDeliverySender;
import org.springframework.stereotype.Component;

/**
 * EMAIL 渠道的 delivery sender。
 *
 * <p>关键词：邮件投递, provider sender, retry circuit breaker, email delivery,
 * SendGrid SES, メール配信(メールはいしん)。</p>
 *
 * <p>当前没有 User 域，所以 email 地址由 recipientKey 合成；真实系统会在本类内替换为
 * customer/contact 查询或调用用户服务。worker 不需要知道这些渠道细节。</p>
 *
 * <p>类名不带 Simulated：本类是接真实 provider 后仍然保留的真实发送代码（模板渲染、Feign 调用、
 * Resilience4j），届时只改地址解析一行和 base-url。模拟行为都在 HTTP 边界另一侧的
 * SimulatedNotificationProviderController，那个类才是上线时整体消失的部分。</p>
 */
@Component
public class EmailNotificationDeliverySender implements NotificationDeliverySender {

    private final NotificationProviderClient notificationProviderClient;
    private final ResilientCallHelper resilientCallHelper;

    public EmailNotificationDeliverySender(
            NotificationProviderClient notificationProviderClient,
            ResilientCallHelper resilientCallHelper
    ) {
        this.notificationProviderClient = notificationProviderClient;
        this.resilientCallHelper = resilientCallHelper;
    }

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.EMAIL;
    }

    @Override
    public String send(NotificationDelivery delivery) {
        // 阶段 1：当前 demo 用 recipientKey 合成地址。未来接 User 域时，只替换这里的地址解析逻辑。
        String recipientAddress = "user-" + delivery.recipientKey() + "@example.com";
        // 阶段 2：按 delivery 快照渲染文案，不回查 Notification 或业务表，避免发送时被后续业务变更影响。
        String title = NotificationMessageTemplates.titleFor(delivery.notificationType());
        String body = NotificationMessageTemplates.bodyFor(
                delivery.notificationType(), delivery.channel(), delivery.subjectId());
        NotificationProviderClient.NotificationProviderRequest request =
                new NotificationProviderClient.NotificationProviderRequest(
                        "email",
                        channel(),
                        recipientAddress,
                        title,
                        body,
                        delivery.idempotencyKey()
                );
        // 阶段 3：通过 Feign 走真实 HTTP/JSON 边界，并套 Retry + CircuitBreaker。
        // 硬超时由 spring.cloud.openfeign.client.config.notification-provider 控制，不放进 R4j TimeLimiter。
        // 注意：下面的 lambda 在这里只是被创建（捕获 request），不执行；真正的 HTTP 调用发生在
        // helper 内部 decorated.get()，可能 0 次（断路打开/4xx）到 3 次（重试）。request 先在
        // lambda 外构造好，正是为了让所有重试共用同一份内容和同一个 idempotencyKey。
        // 执行时序的逐步展开见 ResilientCallHelper.call 的 javadoc。
        return resilientCallHelper.call(
                "notificationEmail",
                () -> notificationProviderClient.send(request).providerMessageId()
        );
    }
}
