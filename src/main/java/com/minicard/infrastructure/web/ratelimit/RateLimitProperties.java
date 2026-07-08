package com.minicard.infrastructure.web.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * API 入口限流配置。
 *
 * <p>关键词：入口限流, 令牌桶参数, kill switch, API rate limiting,
 * token bucket, burst capacity, 流量制限(りゅうりょうせいげん)。</p>
 *
 * <p>这是<strong>系统保护层</strong>的限流配置，与 {@code risk.velocity} 的业务风控限流分属两层：
 * 前者按调用方维度保护服务自身，超限返回 429；后者按卡维度识别异常用卡行为，超限走风控 decline。
 * 两者阈值语义不同，所以配置也不共用。</p>
 */
// capacity/refill-per-second 是令牌桶仅有的两个自由度：容量决定允许的瞬时突发，
// 补充速率决定允许的持续吞吐。其余（TTL 等）都能由这两个值推导，不额外暴露旋钮。
@ConfigurationProperties(prefix = "api.rate-limit")
public record RateLimitProperties(
        /** 总开关；false 时整个限流装配不注册（bean 级条件，改动需要重启生效）。 */
        boolean enabled,
        /** 令牌桶容量 = 允许的瞬时突发请求数。 */
        int capacity,
        /** 每秒补充令牌数 = 允许的长期持续速率。 */
        double refillPerSecond,
        /**
         * 是否信任 X-Forwarded-For 作为限流维度。默认 false（安全默认值）：
         * 该 header 客户端可伪造，直连场景下攻击者每个请求换一个首段就等于每个请求一个新桶，
         * per-IP 限流被完全绕过。只有部署在自家 LB/反向代理后面、且该代理会重写此 header 时才打开。
         */
        boolean trustForwardedFor
) {

    // record compact constructor 在绑定时 fail fast：容量或速率配成 0/负数时应用直接启动失败，
    // 而不是运行时"所有请求都被拒"或"限流形同虚设"这类更难定位的行为。
    public RateLimitProperties {
        if (capacity <= 0) {
            throw new IllegalArgumentException("api.rate-limit.capacity must be positive");
        }
        if (refillPerSecond <= 0) {
            throw new IllegalArgumentException("api.rate-limit.refill-per-second must be positive");
        }
    }
}
