package com.minicard.infrastructure.web.ratelimit;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 授权入口的限流拦截器（Spring MVC HandlerInterceptor）。
 *
 * <p>关键词：拦截器, 入口限流, preHandle, HandlerInterceptor, rate limit interceptor,
 * 429, インターセプター, 流量制限(りゅうりょうせいげん)。</p>
 *
 * <h3>为什么是 Interceptor 而不是 Filter（interview 对比点）</h3>
 * <p>项目里已有的 {@code HttpRequestLoggingFilter} 是 Servlet Filter：它跑在
 * DispatcherServlet 之前，看不到路由信息，适合"对一切请求都成立"的横切逻辑（日志要覆盖
 * 包括本拦截器 429 在内的所有响应，所以它必须在更外层）。限流只针对特定业务路由
 * （授权热路径），Interceptor 由 Spring MVC 按 path pattern 精确挂载，preHandle 返回前
 * 就能短路请求，且抛出的异常会走 {@code @RestControllerAdvice} 统一映射——错误契约不分叉。</p>
 *
 * <h3>限流维度：为什么是客户端 IP（以及生产里应该是什么）</h3>
 * <p>理想维度是 cardId，但它在 request body 里，而 preHandle 跑在 body 反序列化之前——
 * 提前读 body 会破坏"拒绝要发生在最便宜的时刻"。Idempotency-Key 每个新请求都不同，
 * 不能当维度。所以取客户端 IP：它在 header/连接层就有，能压住单一来源的洪峰。
 * 生产系统的真实答案是<strong>认证后的调用方身份</strong>（client id / user id，从 token 来，
 * 不碰 body）；本项目没有认证层，IP 是诚实的替代并保留这条注释作为面试论述。
 * 逐卡的速率控制已由 risk 模块的 velocity 滑动窗口在业务层承担。</p>
 */
@Slf4j
public class AuthorizationRateLimitInterceptor implements HandlerInterceptor {

    private final RedisTokenBucketRateLimiter rateLimiter;
    private final MeterRegistry meterRegistry;

    public AuthorizationRateLimitInterceptor(
            RedisTokenBucketRateLimiter rateLimiter,
            MeterRegistry meterRegistry
    ) {
        this.rateLimiter = rateLimiter;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler
    ) {
        // path pattern 只挂 /api/authorizations（collection path），GET /{id} 不会进来；
        // 再按 method 收窄到 POST，OPTIONS/HEAD 这类探测请求不消耗令牌。
        if (!"POST".equals(request.getMethod())) {
            return true;
        }

        RateLimitDecision decision = rateLimiter.tryConsume(resolveClientKey(request));
        if (decision.allowed()) {
            return true;
        }

        // 向上取整到秒并保底 1：Retry-After 是整数秒，返回 0 等于邀请客户端立刻重试。
        long retryAfterSeconds = Math.max(1, (decision.retryAfterMillis() + 999) / 1000);
        meterRegistry.counter("api.ratelimit.denied").increment();
        log.warn(
                "api_rate_limit_denied path={} retryAfterSeconds={}",
                request.getRequestURI(),
                retryAfterSeconds
        );
        // 抛异常而不是自己写 response：429 与其他错误码共用 GlobalExceptionHandler 的出口，
        // JSON body 结构和日志行为保持一致。preHandle 抛出的异常同样会被 advice 捕获。
        throw new RateLimitExceededException(retryAfterSeconds);
    }

    /**
     * 解析调用方 key：优先 X-Forwarded-For 的第一跳，否则用直连地址。
     *
     * <p>X-Forwarded-For 可能是"client, proxy1, proxy2"链，第一个才是原始客户端。
     * 信任边界提醒：该 header 由外部可伪造，只有当入口确定经过自家 LB（会覆盖/追加该 header）
     * 时才可信；直接暴露公网时应只用 remoteAddr。</p>
     */
    private String resolveClientKey(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
