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
import org.springframework.dao.DuplicateKeyException;
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
    public boolean claim(String idempotencyKey, Authorization authorization) {
        try {
            // Insert the pending row instead of doing a read-then-write check.
            // The unique index is the source of truth for idempotency ownership
            // across threads and application instances.
            jdbcTemplate.update(
                    """
                    INSERT INTO authorizations (
                        id, idempotency_key, request_fingerprint,
                        card_id, amount, currency, status,
                        decline_reason, created_at, decided_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    authorization.id().toString(),
                    idempotencyKey,
                    authorization.requestFingerprint(),
                    authorization.cardId(),
                    authorization.requestedAmount().amount(),
                    authorization.requestedAmount().currency().getCurrencyCode(),
                    authorization.status().name(),
                    authorization.declineReason().map(Enum::name).orElse(null),
                    Timestamp.from(authorization.createdAt()),
                    authorization.decidedAt().map(Timestamp::from).orElse(null)
            );
            return true;
        } catch (DuplicateKeyException exception) {
            // A duplicate idempotency key means another request already owns
            // this operation. Other constraint violations still fail loudly.
            return false;
        }
    }

    @Override
    public Optional<Authorization> findByIdempotencyKeyForUpdate(String idempotencyKey) {
        // A duplicate insert blocks behind an uncommitted winner. This current
        // read then observes the completed result under REPEATABLE READ and
        // keeps duplicate requests from returning while the winner is deciding.
        return jdbcTemplate.query(
                "SELECT * FROM authorizations WHERE idempotency_key = ? FOR UPDATE",
                ROW_MAPPER,
                idempotencyKey
        ).stream().findFirst();
    }

    @Override
    public void update(Authorization authorization) {
        // Only decision columns are updated after claim. Request identity fields
        // remain immutable, which is important for idempotency auditing.
        jdbcTemplate.update(
                """
                UPDATE authorizations
                SET status = ?, decline_reason = ?, decided_at = ?
                WHERE id = ?
                """,
                authorization.status().name(),
                authorization.declineReason().map(Enum::name).orElse(null),
                authorization.decidedAt().map(Timestamp::from).orElse(null),
                authorization.id().toString()
        );
    }

    private static final class AuthorizationRowMapper implements RowMapper<Authorization> {

        @Override
        public Authorization mapRow(ResultSet resultSet, int rowNum) throws SQLException {
            return Authorization.restore(
                    UUID.fromString(resultSet.getString("id")),
                    resultSet.getString("request_fingerprint"),
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
