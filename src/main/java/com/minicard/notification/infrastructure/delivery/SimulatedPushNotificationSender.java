package com.minicard.notification.infrastructure.delivery;

import com.minicard.notification.application.NotificationDeliveryProperties;
import com.minicard.notification.domain.delivery.NotificationChannel;
import org.springframework.stereotype.Component;

/**
 * APP_PUSH 渠道的模拟 sender。
 *
 * <p>真实实现会调用 APNs/FCM 等推送网关；这里只演示渠道隔离：push 与 email 是两个独立 sender，
 * 各自有独立的 Resilience4j 断路器，一个 provider 故障不会拖垮另一个。</p>
 */
@Component
public class SimulatedPushNotificationSender extends SimulatedChannelSender {

    public SimulatedPushNotificationSender(NotificationDeliveryProperties properties) {
        super(properties);
    }

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.APP_PUSH;
    }

    @Override
    protected String providerName() {
        return "push";
    }
}
