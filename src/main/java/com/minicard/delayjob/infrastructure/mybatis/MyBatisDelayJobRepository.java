package com.minicard.delayjob.infrastructure.mybatis;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.minicard.delayjob.domain.DelayJob;
import com.minicard.delayjob.domain.DelayJobRepository;
import com.minicard.delayjob.domain.DelayJobStatus;
import com.minicard.delayjob.domain.DelayJobType;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

/**
 * DelayJobRepository 的 MyBatis adapter，负责延迟任务表的领取、更新和去重插入。
 *
 * <p>DelayJob 与 Outbox 类似，都是 database-backed work queue；区别是它执行未来业务动作，
 * 例如 authorization expiry，而不是发布消息。</p>
 */
@Repository
public class MyBatisDelayJobRepository implements DelayJobRepository {

    private final DelayJobMapper mapper;

    public MyBatisDelayJobRepository(DelayJobMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public boolean insertIfAbsent(DelayJob job) {
        try {
            // insertIfAbsent 支持业务方重复 schedule 同一个 logical job 时保持幂等。
            mapper.insert(toRow(job));
            return true;
        } catch (DuplicateKeyException exception) {
            return false;
        }
    }

    @Override
    public List<DelayJob> findRunnableBatchForUpdate(Instant now, int limit) {
        // FOR UPDATE SKIP LOCKED 让多个 scheduler pod 可以横向扩展；这里只 claim PENDING。
        return mapper.findRunnableBatchForUpdate(now, limit)
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public List<DelayJob> findStuckProcessingBatchForUpdate(Instant now, int limit) {
        // PROCESSING 超时恢复单独处理，避免正常 poller 同时承担 recovery 语义。
        return mapper.findStuckProcessingBatchForUpdate(now, limit)
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public Optional<DelayJob> findByIdForUpdate(UUID id) {
        return Optional.ofNullable(mapper.findByIdForUpdate(id.toString()))
                .map(this::toDomain);
    }

    @Override
    public void updateExecutionState(DelayJob job) {
        mapper.updateExecutionState(toRow(job));
    }

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
                job.createdAt(),
                job.updatedAt(),
                job.lastError()
        );
    }

    private DelayJob toDomain(DelayJobRow row) {
        return DelayJob.restore(
                UUID.fromString(row.id()),
                DelayJobType.valueOf(row.jobType()),
                row.aggregateType(),
                row.aggregateId(),
                DelayJobStatus.valueOf(row.status()),
                row.attempts(),
                row.scheduledAt(),
                row.nextAttemptAt(),
                row.createdAt(),
                row.updatedAt(),
                row.lastError()
        );
    }
}
