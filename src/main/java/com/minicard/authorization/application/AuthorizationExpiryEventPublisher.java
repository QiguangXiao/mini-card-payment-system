package com.minicard.authorization.application;

import com.minicard.authorization.domain.Authorization;

/**
 * Application port for recording an expired authorization integration event.
 *
 * <p>The expiry use case depends on this business intent rather than directly
 * depending on Kafka or the Outbox implementation.</p>
 */
public interface AuthorizationExpiryEventPublisher {

    void append(Authorization authorization);
}
