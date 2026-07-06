package com.minicard.statement.application;

import java.time.Instant;
import java.time.LocalDate;
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

    boolean existsForCycle(LocalDate periodStart, LocalDate periodEnd);

    List<StatementJob> findClaimableBatchForUpdate(Instant now, int limit);

    List<StatementJob> findStuckProcessingBatchForUpdate(Instant now, int limit);

    Optional<StatementJob> findByIdForUpdate(UUID id);

    void updateExecutionState(StatementJob job);

    /**
     * 无锁快查：本 worker 是否仍持有该 job 的 PROCESSING lease（status + claim_token 同时匹配）。
     *
     * <p>供 handler 在长账户循环的中途做廉价检查：lease 已被 recoverer/新 worker 接管时，
     * 旧 worker 尽早放弃剩余账户，而不是整片跑完才在 finalize 发现 token 不符。
     * 只读不加锁——它是提前止损的信号，最终一致性防线仍是 finalize 的 FOR UPDATE + token 校验。</p>
     */
    boolean holdsCurrentLease(UUID jobId, String claimToken);
}
