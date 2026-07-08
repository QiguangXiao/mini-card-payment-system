package com.minicard.infrastructure.web.ratelimit;

/**
 * 一次限流判定的结果。
 *
 * <p>关键词：限流判定, 降级放行, retry after, rate limit decision,
 * fail-open, 判定結果(はんていけっか)。</p>
 *
 * <p>结构对齐 {@code VelocityCheckResult}：用 degraded 标记"Redis 不可用时的降级放行"，
 * 让调用方（和 metrics）能区分"真的有余量"和"没查成所以放行"。</p>
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
