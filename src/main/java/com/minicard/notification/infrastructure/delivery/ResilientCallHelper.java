package com.minicard.notification.infrastructure.delivery;

import java.util.function.Supplier;

import com.minicard.notification.domain.delivery.NotificationDeliveryPermanentException;
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
 * 当前 notification provider 已经通过 Feign 调本地模拟 controller，所以 connect/read timeout
 * 由 {@code spring.cloud.openfeign.client.config.notification-provider} 配置。
 * 超时抛出的异常再进入本 helper，被 Retry 和 CircuitBreaker 统计。这里不引入 Resilience4j TimeLimiter，
 * 避免为了硬超时重新带回额外线程池和 async future。</p>
 *
 * <p>重试预算只放在 Resilience4j 这一层：Spring Cloud OpenFeign 默认 {@code Retryer.NEVER_RETRY}，
 * 这个默认应保持不变。若未来给 Feign 自己也配置 retry，会变成 R4j 次数 × Feign 次数的真实 HTTP 请求，
 * 虽然 idempotencyKey 能降低重复副作用，但会放大 provider 压力和排障噪声。</p>
 *
 * <p>异常分类由 {@link NotificationProviderFeignConfiguration} 先做第一层翻译：
 * timeout、连接失败、5xx 仍按 transient failure 进入 retry；4xx 会被转换成
 * {@link NotificationDeliveryPermanentException}，不做 R4j retry，worker 也会直接 DEAD。</p>
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

    /**
     * 用 Retry + CircuitBreaker 包住一次 provider 调用并执行。
     *
     * <p>阅读本方法先分清"组装"和"执行"两个时刻。providerCall 这个 lambda 在 sender 里被创建时
     * 不发送任何东西：它只是一个装着代码的 Supplier 对象，并捕获了 sender 已构造好的 request 引用。
     * 下面的 decorateSupplier 同样不执行——每包一层只是返回一个新 Supplier，其 get() 先做自己的事
     * 再调用内层 get()。直到 decorated.get() 那一行，执行才真正开始。展开后的等效逻辑：</p>
     *
     * <pre>
     * decorated.get()
     * 1. Retry 进入第 attempt 次循环（最多 max-attempts=3）：
     *    1.1 CircuitBreaker.acquirePermission()：breaker OPEN 时抛 CallNotPermittedException，
     *        内层 lambda 一次都不执行（本轮 0 次 HTTP）
     *    1.2 providerCall.get()：此刻才拆开 lambda——Feign 代理把 request 序列化成 JSON、
     *        发 HTTP、反序列化回执、取 providerMessageId
     *    1.3 成功：breaker 记录 onSuccess（耗时进入 slow-call 统计），返回结果，循环结束
     *    1.4 失败：breaker 记录 onError（失败率统计），异常抛给 Retry
     * 2. Retry 捕获异常：命中 ignore-exceptions（CallNotPermitted/Permanent）直接上抛不重试；
     *    否则按指数退避等待 200ms、400ms 后回到步骤 1。重试执行的是同一个 lambda，
     *    因此三次尝试用同一份 request 和同一个 idempotencyKey。
     * </pre>
     *
     * <p>所以一次 call() 触发的真实 HTTP 请求数是 0 次（断路打开或 4xx permanent）、1 次（成功）
     * 或最多 3 次（transient 失败重试）。circuitBreaker 是按名字从 registry 取出的<b>共享有状态实例</b>，
     * 本次调用报告的成功/失败会累积进下一条 delivery 看到的滑动窗口——这是它能"熔断"的前提。</p>
     */
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
        } catch (NotificationDeliveryPermanentException exception) {
            // 4xx 已由 ErrorDecoder 翻译成 permanent failure。不要包装成 IllegalStateException，
            // 否则 worker 看不到类型，只能按 transient failure 消耗 durable retry。
            throw exception;
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
