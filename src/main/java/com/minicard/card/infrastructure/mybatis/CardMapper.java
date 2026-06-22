package com.minicard.card.infrastructure.mybatis;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * Card MyBatis mapper。
 *
 * <p>关键词：卡片 SQL, 卡查询, MyBatis, card mapper,
 * card lookup, カードSQL, カード検索(カードけんさく)。</p>
 */
// @Mapper 让 MyBatis 在运行期生成 proxy，并交给 Spring 容器注入。
// 如果没有这个注解/扫描，CardRepository adapter 的 constructor injection 会找不到实现。
@Mapper
public interface CardMapper {

    /**
     * 按 card id 查询卡片。
     */
    // @Param 固定 XML 里的参数名。省略后多参数方法尤其容易退化成 param1/arg0，XML 绑定会运行期失败。
    CardRow findById(@Param("cardId") String cardId);
}
