package com.minicard.transaction.application;

import com.minicard.transaction.domain.event.CardTransactionDomainEvent;

/**
 * Transaction application layer 用来持久记录 CardTransaction domain events 的 port。
 *
 * <p>PostingService 只发布“交易已入账”这类业务事实，不直接依赖 Kafka 或 Notification。
 * 当前 infrastructure 会把事件写成 Outbox rows，再由后台 worker 做 reliable delivery。</p>
 */
public interface CardTransactionDomainEventPublisher {

    void append(CardTransactionDomainEvent event);
}
