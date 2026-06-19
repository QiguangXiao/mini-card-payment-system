package com.minicard.statement.domain;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface StatementRepository {

    /**
     * 用 creditAccountId + periodStart + periodEnd 唯一索引做账单周期 idempotency。
     */
    boolean insert(Statement statement);

    /**
     * 锁住同一账户同一期账单，保证并发生成请求读到同一个最终结果。
     */
    Optional<Statement> findByCycleForUpdate(
            UUID creditAccountId,
            LocalDate periodStart,
            LocalDate periodEnd
    );

    Optional<Statement> findById(UUID id);
}
