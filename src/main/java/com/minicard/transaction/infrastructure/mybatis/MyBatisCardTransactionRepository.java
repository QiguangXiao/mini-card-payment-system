package com.minicard.transaction.infrastructure.mybatis;

import java.util.Currency;
import java.util.Optional;
import java.util.UUID;

import com.minicard.authorization.domain.Money;
import com.minicard.transaction.domain.CardTransaction;
import com.minicard.transaction.domain.CardTransactionRepository;
import com.minicard.transaction.domain.CardTransactionStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

/**
 * CardTransactionRepository 的 MyBatis adapter。
 *
 * <p>它只负责 row/domain mapping 和 duplicate-key idempotency claim；
 * posting 的业务顺序仍放在 PostingService，避免 SQL 层偷偷承担业务规则。</p>
 */
@Repository
public class MyBatisCardTransactionRepository implements CardTransactionRepository {

    private final CardTransactionMapper mapper;

    public MyBatisCardTransactionRepository(CardTransactionMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public boolean claim(CardTransaction transaction) {
        try {
            // INSERT-first claim：network_transaction_id 唯一索引选择唯一 winner。
            // 只把 DuplicateKeyException 当成幂等重复，其他 DB 错误继续抛出。
            mapper.insert(toRow(transaction));
            return true;
        } catch (DuplicateKeyException exception) {
            return false;
        }
    }

    @Override
    public Optional<CardTransaction> findByNetworkTransactionIdForUpdate(
            String networkTransactionId
    ) {
        return Optional.ofNullable(
                mapper.findByNetworkTransactionIdForUpdate(networkTransactionId)
        ).map(this::toDomain);
    }

    @Override
    public void update(CardTransaction transaction) {
        mapper.update(toRow(transaction));
    }

    private CardTransactionRow toRow(CardTransaction transaction) {
        return new CardTransactionRow(
                transaction.id().toString(),
                transaction.networkTransactionId(),
                transaction.authorizationId().toString(),
                transaction.cardId(),
                transaction.creditAccountId().toString(),
                transaction.amount().amount(),
                transaction.amount().currency().getCurrencyCode(),
                transaction.status().name(),
                transaction.presentmentReceivedAt(),
                transaction.postedAt(),
                transaction.createdAt(),
                transaction.updatedAt()
        );
    }

    private CardTransaction toDomain(CardTransactionRow row) {
        return CardTransaction.restore(
                UUID.fromString(row.id()),
                row.networkTransactionId(),
                UUID.fromString(row.authorizationId()),
                row.cardId(),
                UUID.fromString(row.creditAccountId()),
                new Money(row.amount(), Currency.getInstance(row.currency())),
                CardTransactionStatus.valueOf(row.status()),
                row.presentmentReceivedAt(),
                row.postedAt(),
                row.createdAt(),
                row.updatedAt()
        );
    }
}
