package com.minicard.repayment.infrastructure.mybatis;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * Repayment MyBatis mapper。
 *
 * <p>关键词：还款 SQL, 幂等锁, 行锁, repayment mapper,
 * idempotency lock, row lock, 入金SQL(にゅうきんSQL),
 * 冪等ロック(べきとうロック)。</p>
 *
 * <p>@Mapper 让 MyBatis 生成代理；真正的 SELECT ... FOR UPDATE 写在 XML 中。</p>
 */
@Mapper
public interface RepaymentMapper {

    /**
     * 插入 repayment；idempotency_key 唯一约束先占位，避免重复请求双入账。
     */
    int insert(RepaymentRow repayment);

    /**
     * 根据 idempotency key 加 FOR UPDATE 查询。
     *
     * <p>重复请求会等待 winner transaction 完成，然后比较 fingerprint 并返回同一结果。</p>
     */
    RepaymentRow findByIdempotencyKeyForUpdate(@Param("idempotencyKey") String idempotencyKey);

    /**
     * 按 repayment id 查询。
     */
    RepaymentRow findById(@Param("id") String id);

    /**
     * 更新 repayment 状态，从 PENDING 到 RECEIVED。
     */
    int update(RepaymentRow repayment);
}
