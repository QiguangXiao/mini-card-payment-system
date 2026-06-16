package com.minicard.scheduling.domain;

import java.time.Instant;
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
     * 锁定下一条到期任务，通常由 FOR UPDATE SKIP LOCKED 支持多实例调度。
     */
    Optional<DelayJob> findNextRunnableForUpdate(Instant now);

    Optional<DelayJob> findByIdForUpdate(UUID id);

    void updateExecutionState(DelayJob job);
}
