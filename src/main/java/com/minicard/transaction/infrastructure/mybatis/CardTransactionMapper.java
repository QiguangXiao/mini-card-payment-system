package com.minicard.transaction.infrastructure.mybatis;

import java.time.Instant;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * CardTransaction MyBatis mapper。
 *
 * <p>关键词：交易 SQL, 幂等锁, 账单分配锁, card transaction mapper,
 * idempotency lock, statement assignment, 取引SQL(とりひきSQL),
 * 行ロック(ぎょうロック)。</p>
 */
// @Mapper 生成 MyBatis proxy；SQL 保留在 XML 中，方便明确 FOR UPDATE 和批量 update。
@Mapper
public interface CardTransactionMapper {

    /**
     * 插入 presentment transaction。
     */
    int insert(CardTransactionRow transaction);

    /**
     * 按 networkTransactionId 加 FOR UPDATE，保证 presentment idempotency。
     */
    CardTransactionRow findByNetworkTransactionIdForUpdate(
            @Param("networkTransactionId") String networkTransactionId
    );

    /**
     * 查询某账户某账期内未出账的 POSTED 交易并加锁。
     *
     * <p>Statement generation 会锁住这些行，防止同一交易被两个 statement 同时分配。</p>
     */
    List<CardTransactionRow> findUnbilledPostedByCreditAccountForUpdate(
            // 多参数 mapper 必须显式 @Param，XML 才能稳定绑定时间窗口和账户 id。
            @Param("creditAccountId") String creditAccountId,
            @Param("postedAtFromInclusive") Instant postedAtFromInclusive,
            @Param("postedAtToExclusive") Instant postedAtToExclusive
    );

    /**
     * 批量把交易分配给 statement。
     */
    // XML 中用 <foreach> 一次批量更新；如果 Java 循环逐条 update，会增加 DB round trip 和锁持有时间。
    int assignStatement(@Param("transactions") List<CardTransactionRow> transactions);

    /**
     * 更新 transaction 状态，例如 PENDING -> POSTED。
     */
    int update(CardTransactionRow transaction);
}
