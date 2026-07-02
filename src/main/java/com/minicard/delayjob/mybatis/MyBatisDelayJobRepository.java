package com.minicard.delayjob.mybatis;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.minicard.delayjob.DelayJob;
import com.minicard.delayjob.DelayJobRepository;
import com.minicard.delayjob.DelayJobStatus;
import com.minicard.delayjob.DelayJobType;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

/**
 * DelayJobRepository 的 MyBatis adapter，负责延迟任务表的领取、更新和去重插入。
 *
 * <p>DelayJob 与 Outbox 类似，都是 database-backed work queue；区别是它执行未来业务动作，
 * 例如 authorization expiry，而不是发布消息。</p>
 */
@Repository
@RequiredArgsConstructor
public class MyBatisDelayJobRepository implements DelayJobRepository {

    private final DelayJobMapper mapper;

    @Override
    /**
     * 幂等写入一条 delay job；已存在同一 logical job 时返回 false。
     */
    public boolean insertIfAbsent(DelayJob job) {
        try {
            // insertIfAbsent 支持业务方重复 schedule 同一个 logical job 时保持幂等。
            mapper.insert(toRow(job));
            return true;
        } catch (DuplicateKeyException exception) {
            // 唯一键冲突代表同一个 future action 已经计划过，不应该触发重试/告警。
            return false;
        }
    }

    @Override
    /**
     * 锁定一批当前可执行的 PENDING jobs，供 claimer 写入 PROCESSING lease。
     */
    public List<DelayJob> findRunnableBatchForUpdate(Instant now, int limit) {
        // FOR UPDATE SKIP LOCKED 让多个 scheduler pod 可以横向扩展；这里只 claim PENDING。
        return mapper.findRunnableBatchForUpdate(now, limit)
                .stream()
                // mapper 返回 row DTO，repository adapter 负责转回 domain state object。
                .map(this::toDomain)
                .toList();
    }

    @Override
    /**
     * 锁定一批 lease 已超时的 PROCESSING jobs，供 recoverer 恢复。
     */
    public List<DelayJob> findStuckProcessingBatchForUpdate(Instant now, int limit) {
        // PROCESSING 超时恢复单独处理，避免正常 poller 同时承担 recovery 语义。
        return mapper.findStuckProcessingBatchForUpdate(now, limit)
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    /**
     * 按 id 锁定当前 job row，供 worker finalize 前重新校验 lease。
     */
    public Optional<DelayJob> findByIdForUpdate(UUID id) {
        return Optional.ofNullable(mapper.findByIdForUpdate(id.toString()))
                .map(this::toDomain);
    }

    @Override
    /**
     * 更新 DelayJob 执行状态字段。
     */
    public void updateExecutionState(DelayJob job) {
        mapper.updateExecutionState(toRow(job));
    }

    /**
     * 将 DelayJob domain object 转成数据库 row DTO。
     */
    private DelayJobRow toRow(DelayJob job) {
        return new DelayJobRow(
                job.id().toString(),
                job.jobType().name(),
                job.aggregateType(),
                job.aggregateId(),
                job.status().name(),
                job.attempts(),
                job.scheduledAt(),
                job.nextAttemptAt(),
                job.leaseToken(),
                job.createdAt(),
                job.updatedAt(),
                job.lastError()
        );
    }

    /**
     * 将数据库 row DTO 还原成带状态机校验的 DelayJob。
     */
    private DelayJob toDomain(DelayJobRow row) {
        return DelayJob.restore(
                UUID.fromString(row.id()),
                // String -> enum 的转换集中在 adapter；service/worker 不应该处理数据库字符串状态。
                DelayJobType.valueOf(row.jobType()),
                row.aggregateType(),
                row.aggregateId(),
                DelayJobStatus.valueOf(row.status()),
                row.attempts(),
                row.scheduledAt(),
                row.nextAttemptAt(),
                row.leaseToken(),
                row.createdAt(),
                row.updatedAt(),
                row.lastError()
        );
    }
}
