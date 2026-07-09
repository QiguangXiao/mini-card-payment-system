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
 *
 * <h3>X-Forwarded-For 的信任边界（security review 修正点）</h3>
 * <p>默认<strong>不信任</strong> XFF、只用 remoteAddr：该 header 客户端可伪造，最左段恰恰是
 * 攻击者可控的一段——直连场景每个请求伪造一个不同首段，等于每个请求一个新桶，限流归零。
 * 部署在自家 LB 后面时打开 {@code api.rate-limit.trust-forwarded-for}；这不是可选优化而是
 * 按部署形态二选一：LB 后面不开的话所有流量共享 LB 的 IP，限流器退化成全局限流。
 * 更正统的生产做法是配 {@code server.tomcat.remoteip.*}（RemoteIpValve，按 trusted proxies
 * 解析后 getRemoteAddr() 直接返回真实客户端 IP），应用代码永远只读 remoteAddr。</p>
 */
@Slf4j
public class AuthorizationRateLimitInterceptor implements HandlerInterceptor {

    private final RedisTokenBucketRateLimiter rateLimiter;
    private final RateLimitProperties properties;
    private final MeterRegistry meterRegistry;

    public AuthorizationRateLimitInterceptor(
            RedisTokenBucketRateLimiter rateLimiter,
            RateLimitProperties properties,
            MeterRegistry meterRegistry
    ) {
        this.rateLimiter = rateLimiter;
        this.properties = properties;
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
     * 解析调用方 key：默认 remoteAddr；仅在显式信任反向代理时才读 X-Forwarded-For 首跳。
     *
     * <p>X-Forwarded-For 是"client, proxy1, proxy2"链，第一段才是原始客户端——
     * 但也正因为第一段离服务端最远，它完全由请求方书写。注释不是防御，默认行为才是：
     * trust-forwarded-for 默认 false，未显式配置时伪造的 header 直接被忽略。</p>
     */
    private String resolveClientKey(HttpServletRequest request) {
        // 第 1 步（默认分支 / 安全默认值）：不信任 X-Forwarded-For。
        // trust-forwarded-for 默认 false，此时永远只取 getRemoteAddr()——即 TCP 连接的对端地址，
        // "谁真的和我建了这条 socket"的物理事实，客户端伪造不了。
        // 直连部署下攻击者无论怎么塞 X-Forwarded-For 都改变不了这里的分桶维度，
        // 也就堵死了"每个请求换一个伪造 IP = 每个请求一个新满桶"的限流绕过。
        if (!properties.trustForwardedFor()) {
            return request.getRemoteAddr();
        }

        // —— 以下几步只在显式配置 trust-forwarded-for=true 时才走到，
        //    语义前提是"我确实部署在自家 LB/反向代理后面，且它会写这个 header"。

        // 第 2 步：读取 X-Forwarded-For。它是一个纯文本 header，形如 "client, proxy1, proxy2"，
        // 由链路上每一跳代理依次往后追加，最左段号称是最初的客户端。
        String forwardedFor = request.getHeader("X-Forwarded-For");

        // 第 3 步：header 存在且非空时，取最左段作为客户端 key。
        // split(",")[0] 拿链首那一段，trim() 去掉逗号后惯例的空格。
        // 边界提醒：最左段"可信"依赖 LB 会覆盖/规整该 header；若 LB 只是单纯追加，
        // 最左段仍可能被客户端预先写入而污染——更严密的做法是从右往左跳过已知可信跳数，
        // 或改用 RemoteIpValve（server.tomcat.remoteip.*），详见类头注释。
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }

        // 第 4 步（信任代理但 header 缺失的兜底）：请求没带 X-Forwarded-For
        // （例如没经过预期代理、直达了应用）时退回 getRemoteAddr()，
        // 保证返回一个合法的分桶 key，而不是 null。
        return request.getRemoteAddr();
    }
}
