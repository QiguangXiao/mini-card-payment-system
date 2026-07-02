package com.minicard.statement.infrastructure.mybatis;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.minicard.statement.application.StatementJobRepository;
import com.minicard.statement.domain.StatementJob;
import com.minicard.support.MySqlIntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * StatementJobRepository 的真 MySQL mapper 验证。
 *
 * <p>关键词：账单任务仓储, cycle exists, MyBatis mapper,
 * statement job repository, reconciliation, 結合テスト。</p>
 */
class StatementJobRepositoryIT extends MySqlIntegrationTestBase {

    @Autowired
    private StatementJobRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanStatementJobs() {
        jdbc.update("DELETE FROM statement_jobs");
    }

    @Test
    void existsForCycleReturnsTrueOnlyWhenThatCycleHasPlannedJobs() {
        StatementJob job = StatementJob.pending(
                LocalDate.parse("2026-06-01"),
                LocalDate.parse("2026-06-30"),
                LocalDate.parse("2026-07-27"),
                0,
                1,
                Instant.parse("2026-07-01T01:00:00Z")
        );

        repository.insertAll(List.of(job));

        assertThat(repository.existsForCycle(
                LocalDate.parse("2026-06-01"),
                LocalDate.parse("2026-06-30")
        )).isTrue();
        assertThat(repository.existsForCycle(
                LocalDate.parse("2026-05-01"),
                LocalDate.parse("2026-05-31")
        )).isFalse();
    }
}
