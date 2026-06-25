package com.minicard.statement.infrastructure.mybatis;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.minicard.statement.application.StatementJobRepository;
import com.minicard.statement.application.StatementJobStatusSummary;
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
    public void insertAll(List<StatementJob> jobs) {
        for (StatementJob job : jobs) {
            mapper.insert(toRow(job));
        }
    }

    @Override
    public List<StatementJob> findClaimableBatchForUpdate(Instant now, int limit) {
        return mapper.findClaimableBatchForUpdate(now, limit)
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public List<StatementJob> findStuckProcessingBatchForUpdate(Instant now, int limit) {
        return mapper.findStuckProcessingBatchForUpdate(now, limit)
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public Optional<StatementJob> findByIdForUpdate(UUID id) {
        return Optional.ofNullable(mapper.findByIdForUpdate(id.toString()))
                .map(this::toDomain);
    }

    @Override
    public void updateExecutionState(StatementJob job) {
        mapper.updateExecutionState(toRow(job));
    }

    @Override
    public StatementJobStatusSummary summarizeByBatchId(UUID batchId) {
        StatementJobStatusSummaryRow row = mapper.summarizeByBatchId(batchId.toString());
        return new StatementJobStatusSummary(
                row.totalCount(),
                row.pendingCount(),
                row.processingCount(),
                row.doneCount(),
                row.deadCount()
        );
    }

    private StatementJobRow toRow(StatementJob job) {
        return new StatementJobRow(
                job.id().toString(),
                job.batchId().toString(),
                job.shardNo(),
                job.shardCount(),
                job.status().name(),
                job.claimedBy(),
                job.claimedAt(),
                job.claimUntil(),
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

    private StatementJob toDomain(StatementJobRow row) {
        return StatementJob.restore(
                UUID.fromString(row.id()),
                UUID.fromString(row.batchId()),
                row.shardNo(),
                row.shardCount(),
                StatementJobStatus.valueOf(row.status()),
                row.claimedBy(),
                row.claimedAt(),
                row.claimUntil(),
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
