package com.minicard.card.infrastructure.mybatis;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CardMapper {

    CardRow findById(@Param("cardId") String cardId);
}
