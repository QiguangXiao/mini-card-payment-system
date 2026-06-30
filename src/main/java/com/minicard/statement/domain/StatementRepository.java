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

    /**
     * 还款、逾期标记等会改变账单状态的 use case 必须用 row lock 读取 statement。
     */
    Optional<Statement> findByIdForUpdate(UUID id);

    /**
     * 只更新还款进度字段和 statement version，账单金额和 line items 仍保持生成时的审计快照。
     */
    void updatePayment(Statement statement);
}
