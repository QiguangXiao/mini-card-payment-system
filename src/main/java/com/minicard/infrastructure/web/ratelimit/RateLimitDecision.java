package com.minicard.infrastructure.web.ratelimit;

/**
 * 一次限流判定的结果。
 *
 * <p>关键词：限流判定, 降级放行, retry after, rate limit decision,
 * fail-open, 判定結果(はんていけっか)。</p>
 *
 * <p>为什么不只返回 boolean：拒绝时 HTTP 层还需要 {@code retryAfterMillis} 生成
 * {@code Retry-After}。Redis 不可用的 fail-open 已由 limiter 内的 unavailable metric 记录；
 * interceptor 不需要再携带一个未使用的 degraded 状态位。</p>
 */
public record RateLimitDecision(
        /** 是否放行本次请求。 */
        boolean allowed,
        /** 被拒时距下一个令牌可用的毫秒数；放行时为 0。 */
        long retryAfterMillis
) {

    // 工厂不能叫 allowed()：record 已为同名组件生成无参访问器，静态同签名方法会编译冲突。
    // 工厂方法让调用点直接表达 allow/deny，避免每处重复填写 boolean 和等待时间。
    public static RateLimitDecision allow() {
        return new RateLimitDecision(true, 0);
    }

    public static RateLimitDecision deny(long retryAfterMillis) {
        return new RateLimitDecision(false, retryAfterMillis);
    }
}
