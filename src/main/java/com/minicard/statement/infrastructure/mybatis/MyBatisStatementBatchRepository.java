package com.minicard.statement.infrastructure.mybatis;

import java.util.UUID;

import com.minicard.statement.application.StatementBatchRepository;
import com.minicard.statement.domain.StatementBatch;
import com.minicard.statement.domain.StatementBatchStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

/**
 * StatementBatchRepository 的 MyBatis 实现。
 *
 * <p>关键词：账单批次持久化, cycle idempotency, MyBatis adapter,
 * statement batch persistence, 請求バッチ永続化(せいきゅうバッチえいぞくか)。</p>
 */
@Repository
@RequiredArgsConstructor
public class MyBatisStatementBatchRepository implements StatementBatchRepository {

    private final StatementBatchMapper mapper;

    @Override
    public boolean insert(StatementBatch batch) {
        try {
            mapper.insert(toRow(batch));
            return true;
        } catch (DuplicateKeyException exception) {
            // period_start + period_end 是 batch creation 的 idempotency key。
            // 如果 daily scheduler 多实例同时触发，只有一个实例真正创建 batch/jobs。
            return false;
        }
    }

    @Override
    public StatementBatch findById(UUID id) {
        StatementBatchRow row = mapper.findById(id.toString());
        if (row == null) {
            throw new IllegalStateException("statement batch not found: " + id);
        }
        return toDomain(row);
    }

    @Override
    public StatementBatch findByIdForUpdate(UUID id) {
        StatementBatchRow row = mapper.findByIdForUpdate(id.toString());
        if (row == null) {
            throw new IllegalStateException("statement batch not found: " + id);
        }
        return toDomain(row);
    }

    @Override
    public void updateState(StatementBatch batch) {
        mapper.updateState(toRow(batch));
    }

    private StatementBatchRow toRow(StatementBatch batch) {
        return new StatementBatchRow(
                batch.id().toString(),
                batch.periodStart(),
                batch.periodEnd(),
                batch.dueDate(),
                batch.status().name(),
                batch.totalAccountCount(),
                batch.targetAccountsPerJob(),
                batch.jobCount(),
                batch.createdAt(),
                batch.completedAt(),
                batch.lastError()
        );
    }

    private StatementBatch toDomain(StatementBatchRow row) {
        return StatementBatch.restore(
                UUID.fromString(row.id()),
                row.periodStart(),
                row.periodEnd(),
                row.dueDate(),
                StatementBatchStatus.valueOf(row.status()),
                row.totalAccountCount(),
                row.targetAccountsPerJob(),
                row.jobCount(),
                row.createdAt(),
                row.completedAt(),
                row.lastError()
        );
    }
}
