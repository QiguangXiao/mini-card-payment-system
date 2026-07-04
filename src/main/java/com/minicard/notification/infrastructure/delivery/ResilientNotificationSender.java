package com.minicard.notification.infrastructure.delivery;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

import com.minicard.notification.domain.delivery.NotificationChannel;
import com.minicard.notification.domain.delivery.NotificationChannelSender;
import com.minicard.notification.domain.delivery.NotificationDeliveryException;
import com.minicard.notification.domain.delivery.NotificationDispatch;
import com.minicard.notification.domain.delivery.NotificationSender;
import com.minicard.notification.domain.delivery.ProviderReceipt;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * 给每个渠道的外部调用套上 Resilience4j：TimeLimiter(超时) + CircuitBreaker(断路) + Retry(重试)。
 *
 * <p>关键词：弹性发送, 超时重试断路, 组合装饰, resilient sender,
 * timeout retry circuit breaker, decorators, 回復性(かいふくせい)。</p>
 *
 * <p>这里用 programmatic Decorators 而非注解，原因有二：
 * 1) 断路器要<b>按渠道隔离</b>(push provider 挂不应该把 email 也熔断)，注解的实例名是静态的，做不到按 channel 选；
 * 2) worker 是同步线程，programmatic 组合能在调用线程内 join，不引入额外 async 语义。
 * 组合顺序(由外到内)：Retry → CircuitBreaker → TimeLimiter → 异步执行 provider 调用。
 * 即：每次重试都经过断路器与超时；超时/断路打开/provider 异常都被统一包成 NotificationDeliveryException。</p>
 *
 * <p>注意它是 <b>intra-attempt</b> 弹性：在<i>一次</i>投递尝试内做快速、内存级的重试与熔断；
 * 跨重启、跨 pod 的 <b>durable</b> 重试由 NotificationDelivery 状态机(attempts/退避/DEAD)承担。两层互补。</p>
 */
@Component
@Slf4j
public class ResilientNotificationSender implements NotificationSender {

    // 共享的 retry / time limiter 实例名（行为对所有渠道一致）。
    private static final String SHARED_INSTANCE = "notificationDelivery";

    private final Map<NotificationChannel, NotificationChannelSender> senders;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final RetryRegistry retryRegistry;
    private final TimeLimiterRegistry timeLimiterRegistry;
    // supplyAsync 在这个池上跑 provider 调用，使 TimeLimiter 能在超时时放弃等待并取消任务。
    private final Executor senderExecutor;

    public ResilientNotificationSender(
            List<NotificationChannelSender> channelSenders,
            CircuitBreakerRegistry circuitBreakerRegistry,
            RetryRegistry retryRegistry,
            TimeLimiterRegistry timeLimiterRegistry,
            @Qualifier("notificationSenderExecutor") Executor senderExecutor
    ) {
        // 启动时把 List 收成 EnumMap，热路径 O(1) 查找，也能在缺渠道实现时尽早暴露。
        this.senders = sendersByChannel(channelSenders);
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.retryRegistry = retryRegistry;
        this.timeLimiterRegistry = timeLimiterRegistry;
        this.senderExecutor = senderExecutor;
    }

    /**
     * 弹性地发送一次投递；任何失败(超时/断路/重试耗尽/provider 异常)都转成 NotificationDeliveryException。
     */
    @Override
    public ProviderReceipt send(NotificationDispatch dispatch) {
        NotificationChannel channel = dispatch.channel();
        NotificationChannelSender sender = senders.get(channel);
        if (sender == null) {
            // 缺渠道实现是配置错误：抛出去让 worker 记 failure，而不是静默丢消息。
            throw new NotificationDeliveryException(
                    "no sender registered for channel " + channel, null);
        }

        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(circuitBreakerName(channel));
        Retry retry = retryRegistry.retry(SHARED_INSTANCE);
        TimeLimiter timeLimiter = timeLimiterRegistry.timeLimiter(SHARED_INSTANCE);

        Supplier<CompletableFuture<ProviderReceipt>> futureSupplier =
                () -> CompletableFuture.supplyAsync(() -> sender.send(dispatch), senderExecutor);

        // 由内到外装饰：先超时，再断路，最外层重试。
        Callable<ProviderReceipt> decorated = Retry.decorateCallable(
                retry,
                CircuitBreaker.decorateCallable(
                        circuitBreaker,
                        TimeLimiter.decorateFutureSupplier(timeLimiter, futureSupplier)
                )
        );

        try {
            return decorated.call();
        } catch (Exception exception) {
            // CallNotPermittedException(断路打开) / TimeoutException / provider 异常 都到这里，统一上抛。
            throw new NotificationDeliveryException(
                    "delivery failed for channel " + channel + ": " + exception.getMessage(), exception);
        }
    }

    private String circuitBreakerName(NotificationChannel channel) {
        // 每渠道独立断路器：email provider brownout 不会连带把 push 熔断。
        return switch (channel) {
            case APP_PUSH -> "notificationPush";
            case EMAIL -> "notificationEmail";
        };
    }

    private Map<NotificationChannel, NotificationChannelSender> sendersByChannel(
            List<NotificationChannelSender> channelSenders
    ) {
        Map<NotificationChannel, NotificationChannelSender> result = new EnumMap<>(NotificationChannel.class);
        for (NotificationChannelSender sender : channelSenders) {
            NotificationChannelSender previous = result.put(sender.channel(), sender);
            if (previous != null) {
                // 同一渠道两个实现是配置歧义，启动即失败，避免运行期随机用到其中一个。
                throw new IllegalStateException("duplicate sender for channel " + sender.channel());
            }
        }
        EnumSet<NotificationChannel> missingChannels = EnumSet.allOf(NotificationChannel.class);
        missingChannels.removeAll(result.keySet());
        if (!missingChannels.isEmpty()) {
            // 缺 sender 是启动期 wiring/config 错误，不是某条 delivery 的 transient provider failure。
            // fail fast 可以避免某个渠道的所有投递运行后才一路 retry 到 DEAD。
            throw new IllegalStateException("missing sender for channel(s) " + missingChannels);
        }
        return result;
    }
}
