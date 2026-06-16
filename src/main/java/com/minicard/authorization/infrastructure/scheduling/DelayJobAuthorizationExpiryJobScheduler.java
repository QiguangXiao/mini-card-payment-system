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
 * 把 authorization expiry plan 写入通用 delay_jobs 表。
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

        // insertIfAbsent() 和 AuthorizationService 的 APPROVED 写入同事务。
        // 如果 approval rollback，延迟释放计划也 rollback，保持 authorization/delay_jobs 一致。
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
