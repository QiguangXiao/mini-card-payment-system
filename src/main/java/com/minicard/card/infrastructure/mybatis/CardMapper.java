package com.minicard.card.infrastructure.mybatis;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * Card MyBatis mapper。
 *
 * <p>关键词：卡片 SQL, 卡查询, MyBatis, card mapper,
 * card lookup, カードSQL, カード検索(カードけんさく)。</p>
 */
@Mapper
public interface CardMapper {

    /**
     * 按 card id 查询卡片。
     */
    CardRow findById(@Param("cardId") String cardId);
}
