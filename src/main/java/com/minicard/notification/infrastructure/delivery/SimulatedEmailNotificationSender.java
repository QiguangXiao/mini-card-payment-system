package com.minicard.notification.infrastructure.delivery;

import com.minicard.notification.application.NotificationDeliveryProperties;
import com.minicard.notification.domain.delivery.NotificationChannel;
import org.springframework.stereotype.Component;

/**
 * EMAIL 渠道的模拟 sender。
 *
 * <p>真实实现会调用 SES/SendGrid 等邮件网关；这里与 push sender 对称，演示"同一通知扇出到多个渠道、
 * 各自独立投递与重试"。</p>
 */
@Component
public class SimulatedEmailNotificationSender extends SimulatedChannelSender {

    public SimulatedEmailNotificationSender(NotificationDeliveryProperties properties) {
        super(properties);
    }

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.EMAIL;
    }

    @Override
    protected String providerName() {
        return "email";
    }
}
