package com.minicard.authorization.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Currency;
import java.util.Optional;
import java.util.UUID;

import com.minicard.authorization.domain.Authorization;
import com.minicard.authorization.domain.AuthorizationDeclineReason;
import com.minicard.authorization.domain.AuthorizationRepository;
import com.minicard.authorization.domain.AuthorizationStatus;
import com.minicard.authorization.domain.Money;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAuthorizationRepository implements AuthorizationRepository {

    private static final RowMapper<Authorization> ROW_MAPPER = new AuthorizationRowMapper();

    private final JdbcTemplate jdbcTemplate;

    public JdbcAuthorizationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<Authorization> findById(UUID id) {
        return jdbcTemplate.query(
                "SELECT * FROM authorizations WHERE id = ?",
                ROW_MAPPER,
                id.toString()
        ).stream().findFirst();
    }

    @Override
    public Authorization saveOrGet(String idempotencyKey, Authorization authorization) {
        jdbcTemplate.update(
                """
                INSERT INTO authorizations (
                    id, idempotency_key, card_id, amount, currency, status,
                    decline_reason, created_at, decided_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE id = id
                """,
                authorization.id().toString(),
                idempotencyKey,
                authorization.cardId(),
                authorization.requestedAmount().amount(),
                authorization.requestedAmount().currency().getCurrencyCode(),
                authorization.status().name(),
                authorization.declineReason().map(Enum::name).orElse(null),
                Timestamp.from(authorization.createdAt()),
                authorization.decidedAt().map(Timestamp::from).orElse(null)
        );

        return findByIdempotencyKey(idempotencyKey)
                .orElseThrow(() -> new IllegalStateException(
                        "authorization was not visible after atomic upsert"
                ));
    }

    private Optional<Authorization> findByIdempotencyKey(String idempotencyKey) {
        // FOR UPDATE performs a current read, so the transaction sees the row
        // selected by the upsert even under MySQL REPEATABLE READ.
        return jdbcTemplate.query(
                "SELECT * FROM authorizations WHERE idempotency_key = ? FOR UPDATE",
                ROW_MAPPER,
                idempotencyKey
        ).stream().findFirst();
    }

    private static final class AuthorizationRowMapper implements RowMapper<Authorization> {

        @Override
        public Authorization mapRow(ResultSet resultSet, int rowNum) throws SQLException {
            return Authorization.restore(
                    UUID.fromString(resultSet.getString("id")),
                    resultSet.getString("card_id"),
                    new Money(
                            resultSet.getBigDecimal("amount"),
                            Currency.getInstance(resultSet.getString("currency"))
                    ),
                    AuthorizationStatus.valueOf(resultSet.getString("status")),
                    optionalDeclineReason(resultSet.getString("decline_reason")),
                    resultSet.getTimestamp("created_at").toInstant(),
                    optionalInstant(resultSet.getTimestamp("decided_at"))
            );
        }

        private AuthorizationDeclineReason optionalDeclineReason(String value) {
            return value == null ? null : AuthorizationDeclineReason.valueOf(value);
        }

        private java.time.Instant optionalInstant(Timestamp value) {
            return value == null ? null : value.toInstant();
        }
    }
}
