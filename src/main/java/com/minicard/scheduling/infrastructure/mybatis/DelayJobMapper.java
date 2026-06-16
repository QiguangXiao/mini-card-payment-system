package com.minicard.scheduling.infrastructure.mybatis;

import java.time.Instant;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface DelayJobMapper {

    int insert(DelayJobRow job);

    DelayJobRow findNextRunnableForUpdate(@Param("now") Instant now);

    DelayJobRow findByIdForUpdate(@Param("id") String id);

    int updateExecutionState(DelayJobRow job);
}
