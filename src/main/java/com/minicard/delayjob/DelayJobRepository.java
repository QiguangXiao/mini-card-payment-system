package com.minicard.delayjob;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * DelayJob 的 repository port，抽象 database-backed delayed work queue。
 *
 * <p>它让 application service 只表达 claim/execute/finalize 流程，
 * 不关心 MyBatis 或 SQL 细节。</p>
 */
public interface DelayJobRepository {

    /**
     * 幂等插入延迟任务，避免同一个业务对象重复创建相同 future action。
     */
    boolean insertIfAbsent(DelayJob job);

    /**
     * 批量锁定到期 PENDING 任务，通常由 FOR UPDATE SKIP LOCKED 支持多实例调度。
     */
    List<DelayJob> findRunnableBatchForUpdate(Instant now, int limit);

    /**
     * 批量锁定 lease 已超时的 PROCESSING 任务，供 recoverer 恢复。
     */
    List<DelayJob> findStuckProcessingBatchForUpdate(Instant now, int limit);

    Optional<DelayJob> findByIdForUpdate(UUID id);

    void updateExecutionState(DelayJob job);
}
