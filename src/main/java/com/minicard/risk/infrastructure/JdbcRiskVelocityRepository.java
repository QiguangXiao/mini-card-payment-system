package com.minicard.risk.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class JdbcRiskVelocityRepository {

    private final JdbcTemplate jdbcTemplate;

    public int countRecentAuthorizations(String cardId, Instant since) {
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
