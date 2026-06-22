package com.minicard.statement.domain.event;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.minicard.authorization.domain.Money;

/**
 * 账单周期关闭并生成 statement 的领域事件。
 *
 * <p>这里叫 closed，而不是 created：业务含义是 billing cycle 被固定下来，
 * 后续 Payment、Notification 或账单 PDF 生成可以消费这个事实。</p>
 */
// event record 是不可变消息事实；如果 listener 需要反查库补字段，异步消费会受“当前库状态”影响。
public record StatementClosedDomainEvent(
        UUID statementId,
        UUID creditAccountId,
        LocalDate periodStart,
        LocalDate periodEnd,
        LocalDate dueDate,
        Money totalAmount,
        Money minimumPaymentAmount,
        int transactionCount,
        Instant occurredAt
) implements StatementDomainEvent {
}
