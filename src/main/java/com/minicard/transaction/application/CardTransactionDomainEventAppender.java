package com.minicard.transaction.application;

import com.minicard.transaction.domain.event.CardTransactionDomainEvent;

/**
 * Transaction application layer 用来持久记录 CardTransaction domain events 的 port。
 *
 * <p>关键词：入账事件追加, Outbox 意图, transaction boundary,
 * transaction event append, transactional outbox, 売上イベント追記(ついき)。</p>
 *
 * <p>PostingService 只追加“交易已入账”这类业务事实，不直接依赖 Kafka 或 Notification。
 * 当前 infrastructure 会把事件写成 Outbox rows，再由后台 worker 做 reliable delivery。</p>
 */
public interface CardTransactionDomainEventAppender {

    // append 只记录可靠发布意图；真正 Kafka send 由通用 Outbox worker 完成。
    void append(CardTransactionDomainEvent event);
}
