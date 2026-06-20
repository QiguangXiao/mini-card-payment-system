package com.minicard.risk.application;

import java.time.Instant;

/**
 * 风控 velocity 查询的 application port。
 *
 * <p>当前实现用 JdbcTemplate 做轻量 COUNT 查询，但 application service 不需要知道
 * 具体是 JDBC、MyBatis 还是未来的只读 projection。</p>
 */
public interface RiskVelocityCounter {

    /**
     * 统计某张卡在 since 之后发生过的授权次数。
     */
    int countRecentAuthorizations(String cardId, Instant since);
}
