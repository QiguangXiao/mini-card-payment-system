package com.minicard.risk.application;

import java.time.Instant;

/**
 * 风控 velocity 查询的 application port。
 *
 * <p>关键词：风控 velocity, fail-open policy, degraded signal,
 * risk velocity, ベロシティ確認(ベロシティかくにん)。</p>
 *
 * <p>port 返回结构化结果，而不是裸 int。这样 application service 能区分：
 * 真的查到 0 次，还是 Redis velocity 降级后按 fail-open policy 放行。</p>
 */
public interface RiskVelocityCounter {

    /**
     * 统计某张卡在 since 之后发生过的授权次数，或返回显式降级结果。
     */
    VelocityCheckResult countRecentAuthorizations(String cardId, Instant since);
}
