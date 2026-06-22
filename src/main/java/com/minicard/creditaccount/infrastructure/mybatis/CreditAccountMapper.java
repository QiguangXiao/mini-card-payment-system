package com.minicard.creditaccount.infrastructure.mybatis;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * CreditAccount MyBatis mapper。
 *
 * <p>关键词：额度账户 SQL, 行锁, 余额更新, credit account mapper,
 * row lock, balance update, 利用枠管理SQL(りようわくかんりSQL),
 * 行ロック(ぎょうロック)。</p>
 */
// @Mapper 生成 MyBatis proxy；这个接口本身没有实现类。
// @Param 名称要和 XML 的 #{accountId} 对齐，避免多参数扩展时依赖 param1/param2。
@Mapper
public interface CreditAccountMapper {

    /**
     * 按 account id 加 FOR UPDATE 锁。
     *
     * <p>authorization reservation、presentment posting、repayment 都会改余额，必须串行化。</p>
     */
    CreditAccountRow findByIdForUpdate(@Param("accountId") String accountId);

    /**
     * 更新 reservedAmount/postedBalance/status 等账户字段。
     */
    int update(CreditAccountRow account);
}
