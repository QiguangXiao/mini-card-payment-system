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
 *
 * <p>分片查询的关键是“按账户分片”，不是“按交易分片”。实现类用
 * {@code MOD(CRC32(credit_account_id), shardCount) = shardNo} 先找出本 shard 负责的账户，
 * handler 再让 {@link StatementGenerationService} 对每个账户一次性扫描并锁住该账户本账期所有候选交易。
 * 如果直接按交易 id 分片，同一账户的两笔消费可能落到不同 job，两个 job 就会并发尝试为同一账户同一账期出账。</p>
 */
public interface StatementBillingRepository {

    long countBillableAccounts(Instant periodStartInclusive, Instant periodEndExclusive);

    /**
     * 查询某个 statement job shard 负责的账户列表。
     *
     * <p>{@code shardNo/shardCount} 来自 {@code statement_jobs} row。SQL 使用 MySQL
     * {@code CRC32(credit_account_id)} 生成稳定整数，再取模映射到某个 shard。
     * 同一个 account id 在同一个 {@code shardCount} 下永远得到同一个 {@code shardNo}，
     * 因此这个账户本账期的所有 {@code POSTED + UNBILLED} 交易会被同一个 job 处理。</p>
     */
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
