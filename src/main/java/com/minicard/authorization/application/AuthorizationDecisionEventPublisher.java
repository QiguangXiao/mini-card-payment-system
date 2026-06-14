package com.minicard.authorization.application;

import com.minicard.authorization.domain.Authorization;

/**
 * Application port for recording the integration event caused by a completed
 * authorization decision.
 *
 * <p>The application service depends on this intent, not on Kafka or Outbox.
 * The implementation decides how reliable event publication is achieved.</p>
 */
public interface AuthorizationDecisionEventPublisher {

    void append(Authorization authorization);
}
