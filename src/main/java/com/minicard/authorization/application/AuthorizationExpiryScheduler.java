package com.minicard.authorization.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically drains a bounded number of expired authorization holds.
 *
 * <p>The scheduler deliberately contains no transaction annotation. Calling a
 * separate service bean ensures every expireNext() invocation passes through
 * Spring's transactional proxy and commits independently.</p>
 */
@Component
@ConditionalOnProperty(
        prefix = "authorization.expiry",
        name = "enabled",
        havingValue = "true"
)
public class AuthorizationExpiryScheduler {

    private final AuthorizationExpiryService expiryService;
    private final AuthorizationExpiryProperties properties;

    public AuthorizationExpiryScheduler(
            AuthorizationExpiryService expiryService,
            AuthorizationExpiryProperties properties
    ) {
        this.expiryService = expiryService;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${authorization.expiry.fixed-delay-ms:60000}")
    public void expireOverdueAuthorizations() {
        // Bounding each run prevents a large backlog from monopolizing the
        // scheduler thread and continuously competing with online traffic.
        for (int processed = 0; processed < properties.maxPerRun(); processed++) {
            if (!expiryService.expireNext()) {
                return;
            }
        }
    }
}
