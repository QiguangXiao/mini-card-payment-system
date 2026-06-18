package com.minicard.delayjob.mybatis;

import java.time.Instant;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface DelayJobMapper {

    int insert(DelayJobRow job);

    List<DelayJobRow> findRunnableBatchForUpdate(
            @Param("now") Instant now,
            @Param("limit") int limit
    );

    List<DelayJobRow> findStuckProcessingBatchForUpdate(
            @Param("now") Instant now,
            @Param("limit") int limit
    );

    DelayJobRow findByIdForUpdate(@Param("id") String id);

    int updateExecutionState(DelayJobRow job);
}
