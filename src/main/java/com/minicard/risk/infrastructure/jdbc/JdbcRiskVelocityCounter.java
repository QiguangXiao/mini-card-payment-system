package com.minicard.risk.infrastructure.jdbc;

import java.sql.Timestamp;
import java.time.Instant;

import com.minicard.risk.application.RiskVelocityCounter;
import com.minicard.risk.application.VelocityCheckResult;
import com.minicard.risk.application.VelocitySource;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 基于 JDBC 的 velocity 查询 adapter（对照实现 / comparison implementation）。
 *
 * <p>关键词：风控 velocity, JdbcTemplate, 授权计数, risk velocity,
 * recent authorization count, JDBC, ベロシティチェック,
 * 件数集計(けんすうしゅうけい)。</p>
 *
 * <p>默认实现是 {@link com.minicard.risk.infrastructure.redis.RedisRiskVelocityCounter}
 * （sliding window，主库零读压）。把 risk.velocity.store 设成 jdbc 可切到这里，做
 * “SQL COUNT(*) vs Redis 滑动窗口” 的对照。它直接读 authorizations 表（audit source of truth），
 * 计数精确，但每笔授权都给主库加一次读往返；RiskAssessmentService 只依赖 RiskVelocityCounter
 * port，不知道底层是 JDBC 还是 Redis。</p>
 */
@Repository
@ConditionalOnProperty(prefix = "risk.velocity", name = "store", havingValue = "jdbc")
@RequiredArgsConstructor
public class JdbcRiskVelocityCounter implements RiskVelocityCounter {

    /** JdbcTemplate 用于执行轻量 SQL 聚合，不需要完整 MyBatis mapper。 */
    private final JdbcTemplate jdbcTemplate;

    /**
     * 统计 since 之后某张卡的授权次数。
     */
    @Override
    public VelocityCheckResult countRecentAuthorizations(String cardId, Instant since) {
        // COUNT(*) 可能返回 null 的 JDBC 包装值；下面兜底成 0，避免 NPE 影响授权主流程。
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM authorizations
                WHERE card_id = ?
                  AND created_at >= ?
                """,
                Integer.class,
                cardId,
                // JDBC driver 接收 java.sql.Timestamp 更明确；adapter 层承担 Instant -> SQL temporal type 转换。
                // 如果把这种转换散进 service，application layer 会开始依赖 JDBC 细节。
                Timestamp.from(since)
        );
        return VelocityCheckResult.available(count == null ? 0 : count, VelocitySource.JDBC);
    }
}
