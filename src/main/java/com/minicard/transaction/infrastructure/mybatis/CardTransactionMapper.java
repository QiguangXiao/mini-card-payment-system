package com.minicard.transaction.infrastructure.mybatis;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CardTransactionMapper {

    int insert(CardTransactionRow transaction);

    CardTransactionRow findByNetworkTransactionIdForUpdate(
            @Param("networkTransactionId") String networkTransactionId
    );

    int update(CardTransactionRow transaction);
}
