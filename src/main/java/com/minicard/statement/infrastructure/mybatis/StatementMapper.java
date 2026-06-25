package com.minicard.statement.infrastructure.mybatis;

import java.time.LocalDate;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * Statement MyBatis mapper。
 *
 * <p>关键词：账单 SQL, 行锁, 明细查询, statement mapper,
 * row lock, MyBatis, 請求SQL(せいきゅうSQL), 行ロック(ぎょうロック)。</p>
 *
 * <p>@Mapper 是 MyBatis 与 Spring 集成的高级注解，运行时会生成代理对象并执行 XML SQL。</p>
 */
// @Mapper + XML 的组合让 SQL 锁语义可见、可调优。
// 如果用 ORM 自动生成查询，FOR UPDATE、批量明细插入和索引命中反而更不透明。
@Mapper
public interface StatementMapper {

    /**
     * 插入 statement 主表；唯一约束负责防止同一账户同一账期重复出账。
     */
    int insertStatement(StatementRow statement);

    /**
     * 插入账单明细 snapshot。
     */
    int insertItem(StatementLineRow item);

    /**
     * 按账期查询并加 FOR UPDATE row lock。
     *
     * <p>用于重复请求/并发 batch 时确认已有 statement 状态，避免两个事务同时生成同一期。</p>
     */
    StatementRow findByCycleForUpdate(
            // 多参数 SQL 保留 @Param，避免 XML 依赖 Java 编译参数名。
            @Param("creditAccountId") String creditAccountId,
            @Param("periodStart") LocalDate periodStart,
            @Param("periodEnd") LocalDate periodEnd
    );

    /**
     * 普通只读查询 statement。
     */
    StatementRow findById(@Param("id") String id);

    /**
     * 按 statement id 加 FOR UPDATE row lock；还款入账时需要锁住账单余额。
     */
    StatementRow findByIdForUpdate(@Param("id") String id);

    /**
     * 更新 statement 还款状态和 paidAmount。
     */
    int updatePayment(StatementRow statement);

    /**
     * 查询 statement 明细列表。
     */
    List<StatementLineRow> findItemsByStatementId(@Param("statementId") String statementId);
}
