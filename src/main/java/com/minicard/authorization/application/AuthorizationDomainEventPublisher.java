package com.minicard.authorization.application;

import com.minicard.authorization.domain.event.AuthorizationDomainEvent;

/**
 * Application port for recording Authorization domain events durably.
 *
 * <p>Application layer publishes domain facts. Infrastructure decides whether they become
 * Outbox rows, Kafka messages, or another reliable delivery mechanism.</p>
 */
public interface AuthorizationDomainEventPublisher {

    void append(AuthorizationDomainEvent event);
}
