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
 * <h3>它位于请求链的哪里</h3>
 * <pre>
 * HttpRequestLoggingFilter
 *   -> DispatcherServlet 找到 Controller 路由
 *   -> 本 interceptor.preHandle()
 *   -> Controller 参数绑定 / request body 反序列化
 *   -> AuthorizationService transaction
 * </pre>
 * <p>因此超限请求虽然已经进入 Spring MVC，但还没有解析 body、开启授权事务、访问 Redis 风控或
 * 获取 MySQL row lock。这里拦截的目标不是抵御网络层 DDoS，而是尽早保护应用和数据库热路径。</p>

 * <h3>为什么是 Interceptor 而不是 Filter（interview 对比点）</h3>
 * <p>{@code HttpRequestLoggingFilter} 在 DispatcherServlet 之前运行，适合“所有 HTTP 请求都需要”的
 * request id 和日志；它也能包住本 interceptor 返回的 429。限流只针对一个 MVC endpoint，
 * Interceptor 可以用 path pattern 精确挂载，而且抛出的异常会走 {@code @RestControllerAdvice}
 * 生成统一错误结构。Filter 也能限流，但需要自己判断 URI、处理顺序并写 429 response。</p>
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
        // 阶段 1：WebMvcConfiguration 已按 path 收窄到 /api/authorizations，这里再按 method 收窄。
        // GET /{id} 不匹配该 path；OPTIONS/HEAD 等非授权写请求也不应消耗令牌。
        if (!"POST".equals(request.getMethod())) {
            return true;
        }

        // 阶段 2：先从连接/header 解析分桶 key，再让 Redis Lua 原子地补充并消费一个令牌。
        // 这里只传 client key，不读取 body，所以拒绝发生在 JSON 解析和业务事务之前。
        RateLimitDecision decision = rateLimiter.tryConsume(resolveClientKey(request));
        if (decision.allowed()) {
            return true;
        }

        // 阶段 3：令牌不足时把内部毫秒等待值转换成 HTTP Retry-After 的整数秒。
        // 向上取整并保底 1；返回 0 等于邀请客户端立刻重试，容易形成 retry storm。
        long retryAfterSeconds = Math.max(1, (decision.retryAfterMillis() + 999) / 1000);
        meterRegistry.counter("api.ratelimit.denied").increment();
        // 逐请求 WARN 会让攻击流量同时放大日志量；拒绝规模由 api.ratelimit.denied counter 负责观测。
        log.debug(
                "api_rate_limit_denied path={} retryAfterSeconds={}",
                request.getRequestURI(),
                retryAfterSeconds
        );
        // 阶段 4：抛异常而不是自己写 response。429 与其他错误码共用 GlobalExceptionHandler 的出口，
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
