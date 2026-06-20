package com.minicard.risk.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 基于 JDBC 的 velocity 查询 repository。
 *
 * <p>关键词：风控 velocity, JdbcTemplate, 授权计数, risk velocity,
 * recent authorization count, JDBC, ベロシティチェック,
 * 件数集計(けんすうしゅうけい)。</p>
 */
@Repository
@RequiredArgsConstructor
public class JdbcRiskVelocityRepository {

    /** JdbcTemplate 用于执行轻量 SQL 聚合，不需要完整 MyBatis mapper。 */
    private final JdbcTemplate jdbcTemplate;

    /**
     * 统计 since 之后某张卡的授权次数。
     */
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
                Timestamp.from(since)
        );
        return count == null ? 0 : count;
    }
}
