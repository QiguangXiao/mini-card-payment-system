package com.minicard.creditaccount.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Currency;
import java.util.Optional;
import java.util.UUID;

import com.minicard.authorization.domain.Money;
import com.minicard.creditaccount.domain.CreditAccount;
import com.minicard.creditaccount.domain.CreditAccountRepository;
import com.minicard.creditaccount.domain.CreditAccountStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcCreditAccountRepository implements CreditAccountRepository {

    private static final RowMapper<CreditAccount> ROW_MAPPER = new CreditAccountRowMapper();

    private final JdbcTemplate jdbcTemplate;

    public JdbcCreditAccountRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<CreditAccount> findByIdForUpdate(UUID accountId) {
        // Pessimistic locking serializes reservations for the same account,
        // preventing concurrent authorizations from overspending its limit. The
        // domain reserve() method assumes this lock has already been acquired.
        return jdbcTemplate.query(
                "SELECT * FROM credit_accounts WHERE id = ? FOR UPDATE",
                ROW_MAPPER,
                accountId.toString()
        ).stream().findFirst();
    }

    @Override
    public void update(CreditAccount account) {
        // The aggregate contains the new reservedAmount after reserve(). We only
        // persist that aggregate state; no limit arithmetic is repeated in SQL.
        jdbcTemplate.update(
                "UPDATE credit_accounts SET reserved_amount = ?, status = ? WHERE id = ?",
                account.reservedAmount().amount(),
                account.status().name(),
                account.id().toString()
        );
    }

    private static final class CreditAccountRowMapper implements RowMapper<CreditAccount> {

        @Override
        public CreditAccount mapRow(ResultSet resultSet, int rowNum) throws SQLException {
            Currency currency = Currency.getInstance(resultSet.getString("currency"));
            return CreditAccount.restore(
                    UUID.fromString(resultSet.getString("id")),
                    new Money(resultSet.getBigDecimal("credit_limit"), currency),
                    new Money(resultSet.getBigDecimal("reserved_amount"), currency),
                    CreditAccountStatus.valueOf(resultSet.getString("status"))
            );
        }
    }
}
