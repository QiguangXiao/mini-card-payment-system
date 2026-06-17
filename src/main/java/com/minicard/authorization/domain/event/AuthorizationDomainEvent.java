package com.minicard.authorization.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Authorization bounded context 内部的领域事件 marker。
 *
 * <p>Domain event 只表达业务事实，不知道 Kafka、topic、header 或 Outbox 表结构。</p>
 */
public interface AuthorizationDomainEvent {

    UUID authorizationId();

    Instant occurredAt();
}
