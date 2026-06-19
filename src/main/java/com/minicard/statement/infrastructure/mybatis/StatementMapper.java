package com.minicard.statement.infrastructure.mybatis;

import java.time.LocalDate;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface StatementMapper {

    int insertStatement(StatementRow statement);

    int insertItem(StatementItemRow item);

    StatementRow findByCycleForUpdate(
            @Param("creditAccountId") String creditAccountId,
            @Param("periodStart") LocalDate periodStart,
            @Param("periodEnd") LocalDate periodEnd
    );

    StatementRow findById(@Param("id") String id);

    List<StatementItemRow> findItemsByStatementId(@Param("statementId") String statementId);
}
