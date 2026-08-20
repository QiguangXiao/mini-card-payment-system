package com.minicard.statement.infrastructure.mybatis;

import java.time.Instant;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * Statement billing 专用 SQL mapper。
 *
 * <p>关键词：账单候选 SQL, 待出账账户, 行锁查询,
 * statement billing mapper, billable accounts, 請求バッチSQL(せいきゅうバッチエスキューエル)。</p>
 */
@Mapper
public interface StatementBillingMapper {

    /**
     * 返回本账期仍有 POSTED + UNBILLED 交易的账户。
     *
     * <p>该查询不加锁，只负责给批处理 fan-out 账户列表；账户级生成事务会重新读取并
     * {@code FOR UPDATE} 锁定真实候选行，所以这里读到的即使稍旧也不会造成重复出账。</p>
     */
    List<String> findBillableAccountIds(
            @Param("periodStartInclusive") Instant periodStartInclusive,
            @Param("periodEndExclusive") Instant periodEndExclusive
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
