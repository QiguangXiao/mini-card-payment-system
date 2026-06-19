package com.minicard.statement.application;

import com.minicard.statement.domain.event.StatementDomainEvent;

/**
 * Statement application layer 用来持久记录账单领域事件的 port。
 *
 * <p>StatementService 不直接依赖 Kafka。当前 infrastructure 会把 statement.closed
 * 写成 Outbox rows，再由通用 outbox worker 做 reliable delivery。</p>
 */
public interface StatementDomainEventPublisher {

    void append(StatementDomainEvent event);
}
