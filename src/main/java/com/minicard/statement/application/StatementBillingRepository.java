package com.minicard.statement.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.minicard.statement.domain.StatementLineSource;

/**
 * Statement billing 候选数据仓储。
 *
 * <p>关键词：账单候选, 待出账账户, 交易出账, statement billing repository,
 * billable accounts, billed marker, 請求対象検索(せいきゅうたいしょうけんさく)。</p>
 *
 * <p>它把已经 {@code POSTED + UNBILLED} 的 CardTransaction 读取为 statement line source；
 * StatementGenerationService 不直接知道 SQL 和 row lock 细节。</p>
 */
public interface StatementBillingRepository {

    /**
     * 查询本账期还有待出账交易的账户。
     *
     * <p>返回的是「账户」而不是「交易」：同一账户本期所有消费必须进同一张账单，
     * 所以批处理按账户 fan-out，再由 {@link StatementGenerationService} 一次性锁住该账户全部候选交易。</p>
     *
     * <p>这个查询本身就是批处理的 desired state：账户一旦出账，它的交易被标成 BILLED，
     * 下次就不会再出现在结果里。所以重复跑批是安全的，也不需要一张「本期是否已跑过」的状态表。</p>
     */
    List<UUID> findBillableAccountIds(
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
