package com.minicard.creditaccount.infrastructure.mybatis;

import java.util.Currency;
import java.util.Optional;
import java.util.UUID;

import com.minicard.authorization.domain.Money;
import com.minicard.creditaccount.domain.CreditAccount;
import com.minicard.creditaccount.domain.CreditAccountRepository;
import com.minicard.creditaccount.domain.CreditAccountStatus;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisCreditAccountRepository implements CreditAccountRepository {

    private final CreditAccountMapper creditAccountMapper;

    public MyBatisCreditAccountRepository(CreditAccountMapper creditAccountMapper) {
        this.creditAccountMapper = creditAccountMapper;
    }

    @Override
    public Optional<CreditAccount> findByIdForUpdate(UUID accountId) {
        // The explicit FOR UPDATE SQL lives in the mapper XML. MyBatis only
        // removes JDBC mapping boilerplate; transaction and lock semantics stay
        // visible and are still controlled by AuthorizationService.
        return Optional.ofNullable(creditAccountMapper.findByIdForUpdate(accountId.toString()))
                .map(this::toDomain);
    }

    @Override
    public void update(CreditAccount account) {
        // Persist aggregate state after reserve(); do not duplicate available
        // credit calculations in SQL.
        creditAccountMapper.update(new CreditAccountRow(
                account.id().toString(),
                account.creditLimit().amount(),
                account.reservedAmount().amount(),
                account.creditLimit().currency().getCurrencyCode(),
                account.status().name()
        ));
    }

    private CreditAccount toDomain(CreditAccountRow row) {
        Currency currency = Currency.getInstance(row.currency());
        return CreditAccount.restore(
                UUID.fromString(row.id()),
                new Money(row.creditLimit(), currency),
                new Money(row.reservedAmount(), currency),
                CreditAccountStatus.valueOf(row.status())
        );
    }
}
