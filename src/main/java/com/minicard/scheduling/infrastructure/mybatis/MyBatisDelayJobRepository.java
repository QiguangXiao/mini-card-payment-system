package com.minicard.scheduling.infrastructure.mybatis;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.minicard.scheduling.domain.DelayJob;
import com.minicard.scheduling.domain.DelayJobRepository;
import com.minicard.scheduling.domain.DelayJobStatus;
import com.minicard.scheduling.domain.DelayJobType;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisDelayJobRepository implements DelayJobRepository {

    private final DelayJobMapper mapper;

    public MyBatisDelayJobRepository(DelayJobMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public boolean insertIfAbsent(DelayJob job) {
        try {
            mapper.insert(toRow(job));
            return true;
        } catch (DuplicateKeyException exception) {
            return false;
        }
    }

    @Override
    public Optional<DelayJob> findNextRunnableForUpdate(Instant now) {
        return Optional.ofNullable(mapper.findNextRunnableForUpdate(now))
                .map(this::toDomain);
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
