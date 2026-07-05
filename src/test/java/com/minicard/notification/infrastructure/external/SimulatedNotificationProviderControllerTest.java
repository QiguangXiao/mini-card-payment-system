package com.minicard.notification.infrastructure.external;

import static org.assertj.core.api.Assertions.assertThat;

import com.minicard.notification.application.NotificationDeliveryProperties;
import com.minicard.notification.domain.delivery.NotificationChannel;
import org.junit.jupiter.api.Test;

class SimulatedNotificationProviderControllerTest {

    private final SimulatedNotificationProviderController controller =
            new SimulatedNotificationProviderController(properties());

    @Test
    // 测试目的：验证模拟 provider 的 provider-side idempotency。
    // variant：同一个 provider + idempotencyKey 重复发送，必须返回同一个 providerMessageId，模拟下游去重。
    void sameProviderAndIdempotencyKeyReturnsSameMessageId() {
        SimulatedNotificationProviderController.SimulatedNotificationProviderRequest request =
                request("push", NotificationChannel.APP_PUSH, "delivery-1");

        SimulatedNotificationProviderController.SimulatedNotificationProviderResponse first = controller.send(request);
        SimulatedNotificationProviderController.SimulatedNotificationProviderResponse duplicate = controller.send(request);

        assertThat(duplicate.providerMessageId()).isEqualTo(first.providerMessageId());
    }

    @Test
    // 测试目的：验证 provider 维度也参与去重 key，避免 push 成功后 email 被误判为重复。
    // variant：同一个 idempotencyKey 但 provider 不同，应各自生成独立 providerMessageId。
    void differentProvidersDoNotDeduplicateEachOther() {
        SimulatedNotificationProviderController.SimulatedNotificationProviderResponse push =
                controller.send(request("push", NotificationChannel.APP_PUSH, "delivery-1"));
        SimulatedNotificationProviderController.SimulatedNotificationProviderResponse email =
                controller.send(request("email", NotificationChannel.EMAIL, "delivery-1"));

        assertThat(email.providerMessageId()).isNotEqualTo(push.providerMessageId());
    }

    private SimulatedNotificationProviderController.SimulatedNotificationProviderRequest request(
            String providerName,
            NotificationChannel channel,
            String idempotencyKey
    ) {
        return new SimulatedNotificationProviderController.SimulatedNotificationProviderRequest(
                providerName,
                channel,
                "recipient-address",
                "title",
                "body",
                idempotencyKey
        );
    }

    private NotificationDeliveryProperties properties() {
        return new NotificationDeliveryProperties(
                "http://localhost:8080",
                true,
                1000,
                5000,
                50,
                30,
                8,
                4,
                100,
                0,
                0
        );
    }
}
