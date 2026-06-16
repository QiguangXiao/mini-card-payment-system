package com.minicard.authorization.infrastructure.scheduling;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import com.minicard.authorization.application.AuthorizationExpiryJobScheduler;
import com.minicard.authorization.domain.Authorization;
import com.minicard.scheduling.domain.DelayJob;
import com.minicard.scheduling.domain.DelayJobRepository;
import com.minicard.scheduling.domain.DelayJobType;
import org.springframework.stereotype.Component;

/**
 * Stores authorization expiry plans in the shared delay_jobs mechanism.
 */
@Component
public class DelayJobAuthorizationExpiryJobScheduler implements AuthorizationExpiryJobScheduler {

    private static final String AGGREGATE_TYPE = "Authorization";

    private final DelayJobRepository delayJobRepository;
    private final Clock clock;

    public DelayJobAuthorizationExpiryJobScheduler(
            DelayJobRepository delayJobRepository,
            Clock clock
    ) {
        this.delayJobRepository = delayJobRepository;
        this.clock = clock;
    }

    @Override
    public void schedule(Authorization authorization) {
        Instant expiresAt = authorization.expiresAt()
                .orElseThrow(() -> new IllegalArgumentException(
                        "approved authorization must have expiresAt"
                ));
        Instant now = Instant.now(clock);

        // This insert participates in AuthorizationService's transaction. If
        // the approval rolls back, the delayed expiry plan rolls back too.
        delayJobRepository.insertIfAbsent(DelayJob.pending(
                UUID.randomUUID(),
                DelayJobType.AUTHORIZATION_EXPIRY,
                AGGREGATE_TYPE,
                authorization.id().toString(),
                expiresAt,
                now
        ));
    }
}
