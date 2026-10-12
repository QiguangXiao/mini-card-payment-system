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

    /** 普通查重不锁缺失记录的 gap；最终并发裁决仍由 claim 的唯一键完成。 */
    Optional<CardTransaction> findByNetworkTransactionId(String networkTransactionId);

    /** claim 失败后读取最新 winner，loser 不修改交易，用共享锁避免 S -> X 升级。 */
    Optional<CardTransaction> findByNetworkTransactionIdForShare(String networkTransactionId);

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
