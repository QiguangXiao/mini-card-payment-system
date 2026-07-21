package com.minicard.notification.application.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import com.minicard.notification.domain.Notification;
import com.minicard.notification.domain.NotificationSubjectType;
import com.minicard.notification.domain.NotificationType;
import com.minicard.notification.domain.delivery.NotificationChannel;
import com.minicard.notification.domain.delivery.NotificationDelivery;
import com.minicard.notification.domain.delivery.NotificationDeliveryRepository;
import com.minicard.notification.domain.delivery.NotificationDeliverySender;
import com.minicard.notification.domain.delivery.NotificationDeliveryStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Notification delivery 的 sender wiring、节流和 durable retry 语义测试。
 *
 * <p>关键词：通知投递, 渠道路由, 不消耗重试次数的节流, notification delivery,
 * durable retry budget, provider throttling, 通知配信(つうちはいしん)。</p>
 *
 * <p>核心边界是“是否已经调用 provider”：HTTP 前拿不到 RateLimiter permit 只延后 nextAttemptAt，
 * 不能增加 attempts；真实 provider timeout/5xx 才消耗 durable retry budget。</p>
 */
class NotificationDeliveryWorkerTest {

    private static final Instant NOW = Instant.parse("2026-07-10T00:00:00Z");

    @Test
    // 测试目的：验证缺 channel sender 属于启动期 wiring/config 错误。
    // variant：只注册 APP_PUSH，缺 EMAIL 时构造器 fail fast，避免运行期 delivery 批量 retry 到 DEAD。
    void constructorFailsFastWhenAChannelSenderIsMissing() {
        assertThatThrownBy(() -> newWorker(List.of(sender(NotificationChannel.APP_PUSH))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing sender")
                .hasMessageContaining("EMAIL");
    }

    @Test
    // 测试目的：本地出站 RateLimiter 在 HTTP 前拒绝时，只释放 lease 并延期，不消耗 delivery attempts。
    // 反事实：若每次本地节流都 attempts+1，provider 尚未收到请求，delivery 却可能被提前推进 DEAD。
    void throttlingReschedulesWithoutConsumingAttempts() {
        NotificationDeliveryRepository repository = mock(NotificationDeliveryRepository.class);
        TransactionOperations transactions = executingTransactions();
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        NotificationDelivery claimed = claimedPushDelivery();
        when(repository.findByIdForUpdate(claimed.id())).thenReturn(Optional.of(claimed));

        NotificationDeliverySender throttledPush = new NotificationDeliverySender() {
            @Override
            public NotificationChannel channel() {
                return NotificationChannel.APP_PUSH;
            }

            @Override
            public String send(NotificationDelivery delivery) {
                throw new NotificationDeliveryThrottledException(
                        "notification provider call throttled for notificationPush",
                        new RuntimeException("no permit")
                );
            }
        };
        NotificationDeliveryWorker worker = new NotificationDeliveryWorker(
                repository,
                properties(),
                List.of(throttledPush, sender(NotificationChannel.EMAIL)),
                Clock.fixed(NOW, ZoneOffset.UTC),
                transactions,
                meterRegistry
        );

        worker.handleClaimedDelivery(claimed);

        assertThat(claimed.status()).isEqualTo(NotificationDeliveryStatus.PENDING);
        assertThat(claimed.attempts()).isZero();
        assertThat(claimed.nextAttemptAt()).isEqualTo(NOW.plusMillis(1000));
        assertThat(claimed.leaseToken()).isNull();
        assertThat(meterRegistry.counter("notification.delivery.throttled").count()).isEqualTo(1.0);
        verify(repository).updateDeliveryState(claimed);
    }

    private NotificationDeliveryWorker newWorker(List<NotificationDeliverySender> senders) {
        return new NotificationDeliveryWorker(
                mock(NotificationDeliveryRepository.class),
                properties(),
                senders,
                Clock.systemUTC(),
                mock(TransactionOperations.class),
                new SimpleMeterRegistry()
        );
    }

    private NotificationDeliveryProperties properties() {
        return new NotificationDeliveryProperties(
                "http://localhost:8080", true, 1000, 5000, 50, 30, 8, 4, 100, 0, 0);
    }

    private NotificationDelivery claimedPushDelivery() {
        Notification notification = Notification.requestFromEvent(
                UUID.randomUUID(),
                NotificationSubjectType.CARD_TRANSACTION,
                "txn-1",
                "card-1",
                NotificationType.CARD_TRANSACTION_POSTED,
                NOW
        );
        NotificationDelivery delivery = NotificationDelivery.pendingFor(
                notification, NotificationChannel.APP_PUSH, NOW);
        delivery.markProcessing(NOW, 30, "lease-token-1");
        return delivery;
    }

    private TransactionOperations executingTransactions() {
        TransactionOperations transactions = mock(TransactionOperations.class);
        doAnswer(invocation -> {
            Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactions).executeWithoutResult(any());
        return transactions;
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
