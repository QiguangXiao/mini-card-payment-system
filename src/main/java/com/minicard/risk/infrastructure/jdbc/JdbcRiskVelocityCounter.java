package com.minicard.risk.infrastructure.jdbc;

import java.sql.Timestamp;
import java.time.Instant;

import com.minicard.risk.application.RiskVelocityCounter;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 基于 JDBC 的 velocity 查询 adapter。
 *
 * <p>关键词：风控 velocity, JdbcTemplate, 授权计数, risk velocity,
 * recent authorization count, JDBC, ベロシティチェック,
 * 件数集計(けんすうしゅうけい)。</p>
 *
 * <p>这是故意保留的 JdbcTemplate 示例；RiskAssessmentService 只依赖
 * RiskVelocityCounter port，不直接依赖 JDBC 细节。</p>
 */
@Repository
@RequiredArgsConstructor
public class JdbcRiskVelocityCounter implements RiskVelocityCounter {

    /** JdbcTemplate 用于执行轻量 SQL 聚合，不需要完整 MyBatis mapper。 */
    private final JdbcTemplate jdbcTemplate;

    /**
     * 统计 since 之后某张卡的授权次数。
     */
    @Override
    public int countRecentAuthorizations(String cardId, Instant since) {
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
        return count == null ? 0 : count;
    }
}
