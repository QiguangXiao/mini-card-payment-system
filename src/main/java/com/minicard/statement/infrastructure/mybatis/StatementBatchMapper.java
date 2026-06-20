package com.minicard.statement.infrastructure.mybatis;

import java.time.Instant;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * Statement batch 专用 MyBatis mapper。
 *
 * <p>关键词：批处理候选账户, 未出账交易, SQL 分页, statement batch mapper,
 * unbilled transactions, limit, 請求バッチ(せいきゅうバッチ),
 * 未請求取引(みせいきゅうとりひき)。</p>
 */
@Mapper
public interface StatementBatchMapper {

    /**
     * 查找在本账期内存在 posted 且未分配 statement 的账户。
     *
     * <p>这里不加锁，真正的并发控制在每个账户生成 statement 时完成：
     * StatementService 会锁 credit account 和待出账 transaction rows。</p>
     */
    List<String> findCreditAccountIdsWithUnbilledPostedTransactions(
            @Param("periodStartInclusive") Instant periodStartInclusive,
            @Param("periodEndExclusive") Instant periodEndExclusive,
            @Param("limit") int limit
    );
}
