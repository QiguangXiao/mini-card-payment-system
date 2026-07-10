package com.minicard.infrastructure.web.ratelimit;

/**
 * 一次限流判定的结果。
 *
 * <p>关键词：限流判定, 降级放行, retry after, rate limit decision,
 * fail-open, 判定結果(はんていけっか)。</p>
 *
 * <p>为什么不只返回 boolean：拒绝时 HTTP 层还需要 {@code retryAfterMillis} 生成
 * {@code Retry-After}；Redis 不可用时虽然同样放行，但 {@code degraded=true} 保留了
 * “正常有令牌”和“限流器没判定成功”的语义差别。当前 unavailable metric 在 limiter 内记录，
 * 这个字段主要用于测试和未来调用方策略，不参与业务正确性。</p>
 */
public record RateLimitDecision(
        /** 是否放行本次请求。 */
        boolean allowed,
        /** 是否因 Redis 不可用而降级放行（fail-open）。 */
        boolean degraded,
        /** 被拒时距下一个令牌可用的毫秒数；放行时为 0。 */
        long retryAfterMillis
) {

    // 工厂不能叫 allowed()：record 已为同名组件生成无参访问器，静态同签名方法会编译冲突。
    // 动词命名 allow/deny 也与 VelocityCheckResult.degradedAllow 的既有风格一致。
    public static RateLimitDecision allow() {
        return new RateLimitDecision(true, false, 0);
    }

    public static RateLimitDecision deny(long retryAfterMillis) {
        return new RateLimitDecision(false, false, retryAfterMillis);
    }

    public static RateLimitDecision degradedAllow() {
        return new RateLimitDecision(true, true, 0);
    }
}
