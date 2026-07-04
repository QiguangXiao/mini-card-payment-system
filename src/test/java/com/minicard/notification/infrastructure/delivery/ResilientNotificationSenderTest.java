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
