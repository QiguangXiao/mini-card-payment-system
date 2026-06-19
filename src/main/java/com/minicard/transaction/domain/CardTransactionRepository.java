package com.minicard.transaction.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CardTransactionRepository {

    /**
     * 用 networkTransactionId 做 presentment idempotency claim。
     */
    boolean claim(CardTransaction transaction);

    Optional<CardTransaction> findByNetworkTransactionIdForUpdate(String networkTransactionId);

    /**
     * 账单生成时锁定 posted 且尚未出账的交易。
     */
    List<CardTransaction> findUnbilledPostedByCreditAccountForUpdate(
            UUID creditAccountId,
            Instant postedAtFromInclusive,
            Instant postedAtToExclusive
    );

    /**
     * 把已经被 Statement snapshot 收录的交易标记为已归账，防止重复出账。
     */
    void assignStatement(List<CardTransaction> transactions);

    void update(CardTransaction transaction);
}
