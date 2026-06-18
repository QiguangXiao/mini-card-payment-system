package com.minicard.transaction.domain;

import java.util.Optional;

public interface CardTransactionRepository {

    /**
     * 用 networkTransactionId 做 presentment idempotency claim。
     */
    boolean claim(CardTransaction transaction);

    Optional<CardTransaction> findByNetworkTransactionIdForUpdate(String networkTransactionId);

    void update(CardTransaction transaction);
}
