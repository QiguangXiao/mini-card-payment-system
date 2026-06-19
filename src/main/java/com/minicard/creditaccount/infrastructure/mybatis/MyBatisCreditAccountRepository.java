package com.minicard.creditaccount.infrastructure.mybatis;

import java.util.Currency;
import java.util.Optional;
import java.util.UUID;

import com.minicard.authorization.domain.Money;
import com.minicard.creditaccount.domain.CreditAccount;
import com.minicard.creditaccount.domain.CreditAccountRepository;
import com.minicard.creditaccount.domain.CreditAccountStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * CreditAccountRepository 的 MyBatis adapter，封装账户额度表的锁定读取和持久化。
 *
 * <p>面试重点：真正的并发控制依赖 mapper XML 里的 SELECT ... FOR UPDATE，
 * 但额度计算仍放在 CreditAccount aggregate 内。</p>
 */
@Repository
@RequiredArgsConstructor
public class MyBatisCreditAccountRepository implements CreditAccountRepository {

    private final CreditAccountMapper creditAccountMapper;

    @Override
    public Optional<CreditAccount> findByIdForUpdate(UUID accountId) {
        // FOR UPDATE SQL 放在 mapper XML。MyBatis 只减少 JDBC mapping 样板，
        // transaction 和 lock 语义仍由 AuthorizationService 显式控制。
        return Optional.ofNullable(creditAccountMapper.findByIdForUpdate(accountId.toString()))
                .map(this::toDomain);
    }

    @Override
    public void update(CreditAccount account) {
        // reserve() 后只持久化 aggregate state，不在 SQL 里重复计算 available credit。
        creditAccountMapper.update(new CreditAccountRow(
                account.id().toString(),
                account.creditLimit().amount(),
                account.reservedAmount().amount(),
                account.postedBalance().amount(),
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
                new Money(row.postedBalance(), currency),
                CreditAccountStatus.valueOf(row.status())
        );
    }
}
