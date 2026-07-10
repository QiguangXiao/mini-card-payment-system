package com.minicard.notification.infrastructure.delivery;

import java.util.function.Supplier;

import com.minicard.notification.domain.delivery.NotificationDeliveryPermanentException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import org.springframework.stereotype.Component;

/**
 * 通知 provider 调用的极薄 Resilience4j helper。
 *
 * <p>关键词：重试断路, 同步装饰, provider resilience, retry circuit breaker,
 * synchronous decorator, 回復性(かいふくせい)。</p>
 *
 * <h3>先理解三个组件分别解决什么</h3>
 * <ul>
 *   <li>{@code Retry}：一次调用遇到短暂失败后，在当前线程内再试几次。</li>
 *   <li>{@code RateLimiter}：我方主动控制调用频率，避免超过 provider quota。</li>
 *   <li>{@code CircuitBreaker}：近期失败过多时暂时 OPEN，让新调用快速失败，不再持续打故障 provider。</li>
 * </ul>
 * <p>它们都不负责 notification 的 DB 状态。跨进程、跨重启的 retry/backoff/DEAD 由
 * {@code NotificationDeliveryWorker} 和 {@code notification_deliveries} 状态机负责；本类只处理
 * “一次 worker attempt 内怎样调用 provider”。如果把两种 retry 混成一层，就难以回答重启后谁继续重试。</p>

 * <p>本类只收住三层装饰的重复代码，不知道 delivery id、收件地址，也不更新 DB。
 * 这是刻意保持的基础设施边界，避免 helper 重新长成承载业务流程的 sender facade。</p>
 *
 * <p>HTTP client 层指 Feign/WebClient/OkHttp/Apache HttpClient/供应商 SDK 这些真正发网络请求的地方。
 * 当前 notification provider 已经通过 Feign 调本地模拟 controller，所以 connect/read timeout
 * 由 {@code spring.cloud.openfeign.client.config.notification-provider} 配置。
 * 超时抛出的异常再进入本 helper，被 Retry / CircuitBreaker 处理。这里不引入
 * Resilience4j TimeLimiter，避免为了硬超时重新带回额外线程池和 async future。</p>
 *
 * <p>这里的 RateLimiter 是出站 client-side throttling：限制“这个 Pod 调 provider 的速度”。
 * 它不同于 API 入口的 Redis 分布式限流；多 Pod 部署时总速率约等于单 Pod 配额乘 Pod 数，
 * 所以生产配置必须按 provider 全局 quota、Pod 数和安全余量拆分。</p>
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
    private final RateLimiterRegistry rateLimiterRegistry;
    private final RetryRegistry retryRegistry;

    public ResilientCallHelper(
            CircuitBreakerRegistry circuitBreakerRegistry,
            RateLimiterRegistry rateLimiterRegistry,
            RetryRegistry retryRegistry
    ) {
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.rateLimiterRegistry = rateLimiterRegistry;
        this.retryRegistry = retryRegistry;
    }

    /**
     * 用 Retry + RateLimiter + CircuitBreaker 包住一次 provider 调用并执行。
     *
     * <p>R4j 的 programmatic decoration 可以理解为“给原函数一层层套包装纸”。
     * {@code decorateSupplier} 只返回新的 Supplier，不会立即发 HTTP；直到调用最外层
     * {@code decorated.get()}，包装才从外向内依次执行。表达式从内向外组装：</p>
     *
     * <pre>
     * Retry(
     *   CircuitBreaker(
     *     RateLimiter(
     *       providerCall
     *     )
     *   )
     * )
     * </pre>
     *
     * <p>执行顺序是 Retry → CircuitBreaker → RateLimiter → Feign。breaker 已 OPEN 时，内层不执行；
     * limiter 无许可时，Feign 不执行；真正的 transient provider failure 才由外层 Retry 发起下一次 attempt。
     * 因此一次 {@code call()} 可能产生 0、1 或最多 3 次真实 HTTP，请勿把“调用 helper 一次”误解成
     * “一定只发一次网络请求”。所有 attempt 复用同一 request 和 idempotencyKey。</p>
     */
    public String call(String circuitBreakerName, Supplier<String> providerCall) {
        // 阶段 1：从 registry 取得“有状态”的保护组件。registry 不是每次 new 一个对象；
        // 同名调用共享失败统计和 permit。名字必须和 application.yml 的 instances.* 对齐。
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(circuitBreakerName);
        RateLimiter rateLimiter = rateLimiterRegistry.rateLimiter(circuitBreakerName);
        // push/email 共用 retry 规则，但 breaker/limiter 分渠道；一个渠道故障或配额耗尽不拖累另一个渠道。
        Retry retry = retryRegistry.retry(SHARED_RETRY_INSTANCE);

        // 阶段 2：只组装调用链，下面三行不会发 HTTP。装饰顺序为
        // Retry(CircuitBreaker(RateLimiter(providerCall)))。
        // 1) Retry 在最外层：每次 transient retry attempt 都重新经过保护链，真正重试时也消耗 provider quota。
        // 2) CircuitBreaker 在 RateLimiter 外层：breaker OPEN 时先快速失败，不消耗本地 rate-limit permit。
        // 3) RateLimiter 拒绝(RequestNotPermitted)会被外层 breaker 看见，所以 application.yml 必须把它
        //    放进 notificationPush/Email 的 circuitbreaker.ignore-exceptions；否则我方主动节流会污染 provider
        //    健康统计。timeout-duration=0 也很关键：不要让 breaker slow-call 统计包含"等待令牌"的时间。
        // 这里没有 TimeLimiter；真实调用的超时由 HTTP client/SDK 抛异常，随后被 retry/breaker 处理。
        Supplier<String> decorated = Retry.decorateSupplier(
                retry,
                CircuitBreaker.decorateSupplier(
                        circuitBreaker,
                        RateLimiter.decorateSupplier(rateLimiter, providerCall)
                )
        );
        try {
            // 阶段 3：这里才是真正的执行点。上面的 decorateSupplier 只是“包函数”，不会发 HTTP；
            // decorated.get() 会先进入 Retry，再进入 CircuitBreaker，再进入 RateLimiter，最后才调用 providerCall.get()。
            // providerCall.get() 内部就是 sender 传进来的 Feign 调用：
            // notificationProviderClient.send(request).providerMessageId()
            // 因此这一行返回时，表示 provider 已经返回 message id；worker 后续才能用它 markSent。
            return decorated.get();
        } catch (NotificationDeliveryPermanentException exception) {
            // 4xx 已由 ErrorDecoder 翻译成 permanent failure。不要包装成 IllegalStateException，
            // 否则 worker 看不到类型，只能按 transient failure 消耗 durable retry。
            throw exception;
        } catch (RuntimeException exception) {
            // RequestNotPermitted(本 pod 对 provider 限速) / CallNotPermittedException(断路打开)
            // / provider exception / retry exhausted 都到这里。
            // worker 会统一 markFailed，推进 durable retry/DEAD；helper 不直接碰 DB 状态机。
            throw new IllegalStateException(
                    "delivery provider call failed for " + circuitBreakerName + ": " + exception.getMessage(),
                    exception
            );
        }
    }
}
