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

    long countBillableAccounts(
            @Param("periodStartInclusive") Instant periodStartInclusive,
            @Param("periodEndExclusive") Instant periodEndExclusive
    );

    List<String> findAccountIdsForJob(
            @Param("periodStartInclusive") Instant periodStartInclusive,
            @Param("periodEndExclusive") Instant periodEndExclusive,
            @Param("shardNo") int shardNo,
            @Param("shardCount") int shardCount
    );

    List<StatementLineSourceRow> findBillableLineSourcesForUpdate(
            @Param("creditAccountId") String creditAccountId,
            @Param("periodStartInclusive") Instant periodStartInclusive,
            @Param("periodEndExclusive") Instant periodEndExclusive
    );

    int markCardTransactionsBilled(
            @Param("statementId") String statementId,
            @Param("cardTransactionIds") List<String> cardTransactionIds,
            @Param("billedAt") Instant billedAt
    );
}
