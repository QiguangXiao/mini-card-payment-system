package com.minicard.statement.infrastructure.mybatis;

import java.time.Instant;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * Statement billing 专用 SQL mapper。
 *
 * <p>关键词：账单分片 SQL, 候选账户, SKIP LOCKED 前置查询,
 * statement billing mapper, shard query, 請求バッチSQL(せいきゅうバッチエスキューエル)。</p>
 */
@Mapper
public interface StatementBillingMapper {

    /**
     * 统计本账期有 POSTED + UNBILLED 交易的账户数，用于估算 statement job 分片数量。
     *
     * <p>这是 planner 的无锁参考计数，不承担防重复出账；真正一致性由 cycle/shard 唯一键、
     * 单账户 row lock 和 statement/card-transaction 唯一约束保护。</p>
     */
    long countBillableAccounts(
            @Param("periodStartInclusive") Instant periodStartInclusive,
            @Param("periodEndExclusive") Instant periodEndExclusive
    );

    /**
     * 按 {@code MOD(CRC32(credit_account_id), shardCount)} 返回当前分片负责的账户。
     *
     * <p>按账户而不是按交易分片，保证同一账户本期所有交易由同一个 job 处理。
     * 该查询不加锁，只负责 fan-out；账户级生成事务会重新读取并锁定真实候选行。</p>
     */
    List<String> findAccountIdsForJob(
            @Param("periodStartInclusive") Instant periodStartInclusive,
            @Param("periodEndExclusive") Instant periodEndExclusive,
            @Param("shardNo") int shardNo,
            @Param("shardCount") int shardCount
    );

    /**
     * 查询并 {@code FOR UPDATE} 锁住某账户本账期全部可出账交易。
     *
     * <p>调用方必须处于 StatementGenerationService 的写事务中，使锁覆盖 statement/lines 插入和
     * BILLED 标记；如果锁在事务外立即释放，并发 worker 可能同时为同一批交易生成账单。</p>
     */
    List<StatementLineSourceRow> findBillableLineSourcesForUpdate(
            @Param("creditAccountId") String creditAccountId,
            @Param("periodStartInclusive") Instant periodStartInclusive,
            @Param("periodEndExclusive") Instant periodEndExclusive
    );

    /**
     * 把已写入 statement lines 的交易原子标记为 BILLED 并关联 statement。
     *
     * <p>SQL 只更新仍为 POSTED + UNBILLED + statement_id IS NULL 的行；adapter 会校验 affected rows
     * 必须等于候选交易数，不一致就回滚，避免“账单已有明细但交易仍可再次出账”。</p>
     */
    int markCardTransactionsBilled(
            @Param("statementId") String statementId,
            @Param("cardTransactionIds") List<String> cardTransactionIds,
            @Param("billedAt") Instant billedAt
    );
}
