package com.minicard.notification.infrastructure.delivery;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.Executor;

import com.minicard.notification.domain.delivery.NotificationChannel;
import com.minicard.notification.domain.delivery.NotificationChannelSender;
import com.minicard.notification.domain.delivery.NotificationDispatch;
import com.minicard.notification.domain.delivery.ProviderReceipt;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.junit.jupiter.api.Test;

class ResilientNotificationSenderTest {

    @Test
    // 测试目的：验证缺 channel sender 属于启动期 wiring/config 错误。
    // variant：只注册 APP_PUSH，缺 EMAIL 时构造器 fail fast，避免运行期 delivery 批量 retry 到 DEAD。
    void constructorFailsFastWhenAChannelSenderIsMissing() {
        assertThatThrownBy(() -> newSender(List.of(sender(NotificationChannel.APP_PUSH))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing sender")
                .hasMessageContaining("EMAIL");
    }

    private ResilientNotificationSender newSender(List<NotificationChannelSender> senders) {
        Executor sameThreadExecutor = Runnable::run;
        return new ResilientNotificationSender(
                senders,
                CircuitBreakerRegistry.ofDefaults(),
                RetryRegistry.ofDefaults(),
                TimeLimiterRegistry.ofDefaults(),
                sameThreadExecutor
        );
    }

    private NotificationChannelSender sender(NotificationChannel channel) {
        return new NotificationChannelSender() {
            @Override
            public NotificationChannel channel() {
                return channel;
            }

            @Override
            public ProviderReceipt send(NotificationDispatch dispatch) {
                return new ProviderReceipt(channel.name() + "-message-id");
            }
        };
    }
}
