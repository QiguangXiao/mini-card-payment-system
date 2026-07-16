package com.minicard.authorization.application;

import com.minicard.authorization.domain.event.AuthorizationDomainEvent;

/**
 * Authorization application layer 用来持久记录 domain events 的 port。
 *
 * <p>关键词：领域事件追加, Outbox 意图, transaction boundary,
 * domain event append, transactional outbox, イベント追記(ついき)。</p>
 *
 * <p>Application service 只追加业务事实，不直接依赖 Kafka。当前 infrastructure
 * 会把事件写成 Outbox rows，再由后台 worker 做 reliable delivery。</p>
 */
public interface AuthorizationDomainEventAppender {

    // append 表达“事务内追加事件”，不是同步发送 Kafka。
    // 如果 service 直接调用 Kafka，DB rollback 后仍可能留下已经发出的幽灵消息。
    void append(AuthorizationDomainEvent event);
}
