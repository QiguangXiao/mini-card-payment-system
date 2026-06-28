package com.minicard.risk.application;

/**
 * velocity 检查结果。
 *
 * <p>关键词：风控 velocity, fail-open, degraded result, risk signal,
 * fallback policy, ベロシティ制限(ベロシティせいげん)。</p>
 *
 * <p>count=0 可能是真正没有近期尝试，也可能是 Redis 不可用后的 fail-open。
 * 这个 record 把两种情况拆开，让 RiskAssessmentService 能把“查到 0”和“降级放行”
 * 分别记录成不同的业务语义和监控指标。</p>
 */
public record VelocityCheckResult(
        int count,
        boolean degraded,
        VelocitySource source
) {

    public VelocityCheckResult {
        if (count < 0) {
            throw new IllegalArgumentException("count must be non-negative");
        }
        if (source == null) {
            throw new IllegalArgumentException("source must not be null");
        }
    }

    public static VelocityCheckResult available(int count, VelocitySource source) {
        return new VelocityCheckResult(count, false, source);
    }

    public static VelocityCheckResult degradedAllow(VelocitySource source) {
        return new VelocityCheckResult(0, true, source);
    }
}
