package com.minicard.notification.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.util.List;

import com.minicard.notification.domain.delivery.NotificationChannel;
import com.minicard.notification.domain.delivery.NotificationDelivery;
import com.minicard.notification.domain.delivery.NotificationDeliveryRepository;
import com.minicard.notification.domain.delivery.NotificationDeliverySender;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

class NotificationDeliveryWorkerTest {

    @Test
    // 测试目的：验证缺 channel sender 属于启动期 wiring/config 错误。
    // variant：只注册 APP_PUSH，缺 EMAIL 时构造器 fail fast，避免运行期 delivery 批量 retry 到 DEAD。
    void constructorFailsFastWhenAChannelSenderIsMissing() {
        assertThatThrownBy(() -> newWorker(List.of(sender(NotificationChannel.APP_PUSH))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing sender")
                .hasMessageContaining("EMAIL");
    }

    private NotificationDeliveryWorker newWorker(List<NotificationDeliverySender> senders) {
        return new NotificationDeliveryWorker(
                mock(NotificationDeliveryRepository.class),
                new NotificationDeliveryProperties(true, 1000, 5000, 50, 30, 8, 4, 100, 0, 0),
                senders,
                Clock.systemUTC(),
                mock(TransactionOperations.class)
        );
    }

    private NotificationDeliverySender sender(NotificationChannel channel) {
        return new NotificationDeliverySender() {
            @Override
            public NotificationChannel channel() {
                return channel;
            }

            @Override
            public String send(NotificationDelivery delivery) {
                return channel.name() + "-message-id";
            }
        };
    }
}
