package com.minicard.statement.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Statement batch 候选账户查询 port。
 *
 * <p>它只找“可能需要出账”的 account id；真正的 row lock、账单幂等和交易归账
 * 仍由 StatementService 在每个账户自己的 transaction boundary 内完成。</p>
 */
public interface StatementBatchRepository {

    List<UUID> findCreditAccountIdsWithUnbilledPostedTransactions(
            Instant periodStartInclusive,
            Instant periodEndExclusive,
            int limit
    );
}
