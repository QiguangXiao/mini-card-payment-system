package com.minicard.statement.infrastructure.mybatis;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.minicard.statement.application.StatementJobRepository;
import com.minicard.statement.domain.StatementJob;
import com.minicard.statement.domain.StatementJobStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * StatementJobRepository 的 MyBatis 实现。
 *
 * <p>关键词：账单任务持久化, SKIP LOCKED, lease finalize,
 * statement job persistence, 請求ジョブ永続化(せいきゅうジョブえいぞくか)。</p>
 */
@Repository
@RequiredArgsConstructor
public class MyBatisStatementJobRepository implements StatementJobRepository {

    private final StatementJobMapper mapper;

    @Override
    /**
     * 幂等写入一个 billing cycle 的 statement job 分片集合。
     */
    public void insertAll(List<StatementJob> jobs) {
        // 逐条 INSERT IGNORE：同一个 cycle 的分片创建是幂等的，重复触发不会产生重复 job。
        for (StatementJob job : jobs) {
            mapper.insert(toRow(job));
        }
    }

    @Override
    /**
     * 判断某个 billing cycle 是否已经规划过 statement job 分片。
     */
    public boolean existsForCycle(LocalDate periodStart, LocalDate periodEnd) {
        return mapper.existsForCycle(periodStart, periodEnd);
    }

    @Override
    /**
     * 锁定一批可领取的 statement jobs，供 dispatcher 写入 PROCESSING lease。
     */
    public List<StatementJob> findClaimableBatchForUpdate(Instant now, int limit) {
        return mapper.findClaimableBatchForUpdate(now, limit)
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    /**
     * 锁定一批 lease 已超时的 statement jobs，供 dispatcher recovery 恢复。
     */
    public List<StatementJob> findStuckProcessingBatchForUpdate(Instant now, int limit) {
        return mapper.findStuckProcessingBatchForUpdate(now, limit)
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    /**
     * 按 id 锁定当前 statement job，供 finalize 前校验 claim token。
     */
    public Optional<StatementJob> findByIdForUpdate(UUID id) {
        return Optional.ofNullable(mapper.findByIdForUpdate(id.toString()))
                .map(this::toDomain);
    }

    @Override
    /**
     * 更新 statement job 的执行状态、lease 和统计字段。
     */
    public void updateExecutionState(StatementJob job) {
        mapper.updateExecutionState(toRow(job));
    }

    /**
     * 将 StatementJob domain object 转成数据库 row DTO。
     */
    private StatementJobRow toRow(StatementJob job) {
        return new StatementJobRow(
                job.id().toString(),
                job.periodStart(),
                job.periodEnd(),
                job.dueDate(),
                job.shardNo(),
                job.shardCount(),
                job.status().name(),
                job.claimedBy(),
                job.claimedAt(),
                job.claimUntil(),
                job.claimToken(),
                job.attemptCount(),
                job.processedAccountCount(),
                job.generatedStatementCount(),
                job.skippedAccountCount(),
                job.failedAccountCount(),
                job.createdAt(),
                job.updatedAt(),
                job.lastError()
        );
    }

    /**
     * 将数据库 row DTO 还原成带状态机校验的 StatementJob。
     */
    private StatementJob toDomain(StatementJobRow row) {
        return StatementJob.restore(
                UUID.fromString(row.id()),
                row.periodStart(),
                row.periodEnd(),
                row.dueDate(),
                row.shardNo(),
                row.shardCount(),
                StatementJobStatus.valueOf(row.status()),
                row.claimedBy(),
                row.claimedAt(),
                row.claimUntil(),
                row.claimToken(),
                row.attemptCount(),
                row.processedAccountCount(),
                row.generatedStatementCount(),
                row.skippedAccountCount(),
                row.failedAccountCount(),
                row.createdAt(),
                row.updatedAt(),
                row.lastError()
        );
    }
}
