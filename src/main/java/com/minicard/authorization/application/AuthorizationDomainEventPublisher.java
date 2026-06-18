package com.minicard.authorization.application;

import com.minicard.authorization.domain.event.AuthorizationDomainEvent;

/**
 * Authorization application layer 用来持久记录 domain events 的 port。
 *
 * <p>Application service 只发布业务事实，不直接依赖 Kafka。当前 infrastructure
 * 会把事件写成 Outbox rows，再由后台 worker 做 reliable delivery。</p>
 */
public interface AuthorizationDomainEventPublisher {

    void append(AuthorizationDomainEvent event);
}
