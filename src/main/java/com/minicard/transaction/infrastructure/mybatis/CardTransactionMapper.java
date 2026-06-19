package com.minicard.transaction.infrastructure.mybatis;

import java.time.Instant;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CardTransactionMapper {

    int insert(CardTransactionRow transaction);

    CardTransactionRow findByNetworkTransactionIdForUpdate(
            @Param("networkTransactionId") String networkTransactionId
    );

    List<CardTransactionRow> findUnbilledPostedByCreditAccountForUpdate(
            @Param("creditAccountId") String creditAccountId,
            @Param("postedAtFromInclusive") Instant postedAtFromInclusive,
            @Param("postedAtToExclusive") Instant postedAtToExclusive
    );

    int assignStatement(@Param("transactions") List<CardTransactionRow> transactions);

    int update(CardTransactionRow transaction);
}
