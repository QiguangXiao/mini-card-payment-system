package com.minicard.statement.infrastructure.mybatis;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * Statement job MyBatis mapper。
 *
 * <p>关键词：账单任务 SQL, FOR UPDATE SKIP LOCKED, lease,
 * statement job mapper, 請求ジョブSQL(せいきゅうジョブエスキューエル)。</p>
 */
@Mapper
public interface StatementJobMapper {

    int insert(StatementJobRow row);

    boolean existsForCycle(
            @Param("periodStart") LocalDate periodStart,
            @Param("periodEnd") LocalDate periodEnd
    );

    List<StatementJobRow> findClaimableBatchForUpdate(
            @Param("now") Instant now,
            @Param("limit") int limit
    );

    List<StatementJobRow> findStuckProcessingBatchForUpdate(
            @Param("now") Instant now,
            @Param("limit") int limit
    );

    StatementJobRow findByIdForUpdate(@Param("id") String id);

    int updateExecutionState(StatementJobRow row);
}
