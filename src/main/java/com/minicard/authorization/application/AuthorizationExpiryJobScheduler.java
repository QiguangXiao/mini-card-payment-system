package com.minicard.authorization.application;

import com.minicard.authorization.domain.Authorization;

/**
 * Application port for planning an authorization expiry delay job.
 *
 * <p>AuthorizationService expresses the business intent here. The adapter can
 * store that plan in the generic delay_jobs table without leaking scheduler
 * table details into the authorization use case.</p>
 */
public interface AuthorizationExpiryJobScheduler {

    void schedule(Authorization authorization);
}
