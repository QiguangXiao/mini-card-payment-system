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
 */
@Component
public class SimulatedEmailNotificationSender implements NotificationDeliverySender {

    private final SimulatedProvider simulatedProvider;
    private final ResilientCallHelper resilientCallHelper;

    public SimulatedEmailNotificationSender(
            SimulatedProvider simulatedProvider,
            ResilientCallHelper resilientCallHelper
    ) {
        this.simulatedProvider = simulatedProvider;
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
        // 阶段 3：同步调用 provider，并套 Retry + CircuitBreaker。真实 HTTP timeout 在 SDK/HTTP client 配。
        return resilientCallHelper.call("notificationEmail", () -> simulatedProvider.send(
                "email",
                channel(),
                recipientAddress,
                title,
                body,
                delivery.idempotencyKey()
        ));
    }
}
