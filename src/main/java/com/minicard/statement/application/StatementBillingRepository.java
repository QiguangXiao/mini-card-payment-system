package com.minicard.statement.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.minicard.statement.domain.StatementLineSource;

/**
 * Statement billing 候选数据仓储。
 *
 * <p>关键词：账单候选, 分片账户, 交易出账, statement billing repository,
 * shard query, billed marker, 請求対象検索(せいきゅうたいしょうけんさく),
 * 分割処理(ぶんかつしょり)。</p>
 *
 * <p>它把 CardTransaction + append-only LedgerEntry 拼成 statement line source；
 * StatementGenerationService 不直接知道 SQL join 和 shard hash 细节。</p>
 */
public interface StatementBillingRepository {

    long countBillableAccounts(Instant periodStartInclusive, Instant periodEndExclusive);

    List<UUID> findAccountIdsForJob(
            Instant periodStartInclusive,
            Instant periodEndExclusive,
            int shardNo,
            int shardCount
    );

    boolean existsUnbilledPostedTransactionMissingLedger(
            UUID creditAccountId,
            Instant periodStartInclusive,
            Instant periodEndExclusive
    );

    List<StatementLineSource> findBillableLineSourcesForUpdate(
            UUID creditAccountId,
            Instant periodStartInclusive,
            Instant periodEndExclusive
    );

    void markCardTransactionsBilled(
            UUID statementId,
            List<StatementLineSource> lineSources,
            Instant billedAt
    );
}
