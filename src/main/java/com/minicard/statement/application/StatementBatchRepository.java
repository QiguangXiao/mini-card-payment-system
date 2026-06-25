package com.minicard.statement.application;

import java.util.UUID;

import com.minicard.statement.domain.StatementBatch;

/**
 * Statement batch 持久化 port。
 *
 * <p>关键词：账单批次仓储, cycle idempotency, batch completion,
 * statement batch repository, durable batch, 請求バッチリポジトリ(せいきゅうバッチリポジトリ)。</p>
 */
public interface StatementBatchRepository {

    boolean insert(StatementBatch batch);

    StatementBatch findById(UUID id);

    StatementBatch findByIdForUpdate(UUID id);

    void updateState(StatementBatch batch);
}
