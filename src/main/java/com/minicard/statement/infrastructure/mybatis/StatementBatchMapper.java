package com.minicard.statement.infrastructure.mybatis;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * Statement batch MyBatis mapper。
 *
 * <p>关键词：账单批次 SQL, cycle unique, row lock,
 * statement batch mapper, 請求バッチSQL(せいきゅうバッチエスキューエル)。</p>
 */
@Mapper
public interface StatementBatchMapper {

    int insert(StatementBatchRow row);

    StatementBatchRow findById(@Param("id") String id);

    StatementBatchRow findByIdForUpdate(@Param("id") String id);

    int updateState(StatementBatchRow row);
}
