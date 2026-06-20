package com.minicard.statement.infrastructure.mybatis;

import java.time.Instant;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface StatementBatchMapper {

    List<String> findCreditAccountIdsWithUnbilledPostedTransactions(
            @Param("periodStartInclusive") Instant periodStartInclusive,
            @Param("periodEndExclusive") Instant periodEndExclusive,
            @Param("limit") int limit
    );
}
