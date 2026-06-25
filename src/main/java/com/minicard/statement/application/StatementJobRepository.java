package com.minicard.statement.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.minicard.statement.domain.StatementJob;

/**
 * Statement job 持久化 port。
 *
 * <p>关键词：账单任务仓储, DB claim, SKIP LOCKED,
 * statement job repository, processing lease, 請求ジョブリポジトリ(せいきゅうジョブリポジトリ)。</p>
 */
public interface StatementJobRepository {

    void insertAll(List<StatementJob> jobs);

    List<StatementJob> findClaimableBatchForUpdate(Instant now, int limit);

    List<StatementJob> findStuckProcessingBatchForUpdate(Instant now, int limit);

    Optional<StatementJob> findByIdForUpdate(UUID id);

    void updateExecutionState(StatementJob job);

    StatementJobStatusSummary summarizeByBatchId(UUID batchId);
}
