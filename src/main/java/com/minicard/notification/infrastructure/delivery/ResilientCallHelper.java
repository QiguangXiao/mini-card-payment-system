package com.minicard.notification.infrastructure.delivery;

import java.util.function.Supplier;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import org.springframework.stereotype.Component;

/**
 * 通知 provider 调用的极薄 Resilience4j helper。
 *
 * <p>关键词：重试断路, 同步装饰, provider resilience, retry circuit breaker,
 * synchronous decorator, 回復性(かいふくせい)。</p>
 *
 * <p>只收住 Retry + CircuitBreaker 的重复装饰代码，不承载 Notification 业务语义：
 * 它不知道 delivery id、channel、provider 地址，也不更新 DB。这样它只是技术 helper，
 * 不会重新长成一个 ResilientNotificationSender 门面。</p>
 *
 * <p>HTTP client 层指 Feign/WebClient/OkHttp/Apache HttpClient/供应商 SDK 这些真正发网络请求的地方。
 * 当前项目调用的是 {@link SimulatedProvider} 的内部函数，所以没有 connect/read timeout 可配；
 * 未来换成 Feign 调 SendGrid/FCM 等外部 API 时，应在 Feign 或底层 HTTP client 配置超时。
 * 超时抛出的异常再进入本 helper，被 Retry 和 CircuitBreaker 统计。这里不引入 Resilience4j TimeLimiter，
 * 避免为了硬超时重新带回额外线程池和 async future。</p>
 */
@Component
public class ResilientCallHelper {

    // 所有通知渠道共用 retry 策略：重试次数/间隔通常是"通知 provider 调用"的共性。
    // CircuitBreaker 则按渠道分开，避免 push provider 故障把 email provider 也熔断。
    private static final String SHARED_RETRY_INSTANCE = "notificationDelivery";

    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final RetryRegistry retryRegistry;

    public ResilientCallHelper(
            CircuitBreakerRegistry circuitBreakerRegistry,
            RetryRegistry retryRegistry
    ) {
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.retryRegistry = retryRegistry;
    }

    public String call(String circuitBreakerName, Supplier<String> providerCall) {
        // circuitBreakerName 由 sender 传入：notificationPush / notificationEmail。
        // 名字要和 application.yml 的 resilience4j.circuitbreaker.instances.* 对齐，否则会用默认配置。
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(circuitBreakerName);
        // retry 实例名固定为 notificationDelivery，对应 application.yml 的 retry.instances.notificationDelivery。
        Retry retry = retryRegistry.retry(SHARED_RETRY_INSTANCE);

        // 装饰顺序：Retry(CircuitBreaker(providerCall))。
        // 每次 retry 都会经过 circuit breaker：如果 breaker 已 OPEN，就快速失败，不继续打慢/坏 provider。
        // 这里没有 TimeLimiter；真实调用的超时由 HTTP client/SDK 抛异常，随后被 retry/breaker 处理。
        Supplier<String> decorated = Retry.decorateSupplier(
                retry,
                CircuitBreaker.decorateSupplier(circuitBreaker, providerCall)
        );
        try {
            // providerCall 返回 providerMessageId。worker 后续用这个回执 id markSent。
            return decorated.get();
        } catch (RuntimeException exception) {
            // CallNotPermittedException(断路打开) / provider exception / retry exhausted 都到这里。
            // worker 会统一 markFailed，推进 durable retry/DEAD；helper 不直接碰 DB 状态机。
            throw new IllegalStateException(
                    "delivery provider call failed for " + circuitBreakerName + ": " + exception.getMessage(),
                    exception
            );
        }
    }
}
