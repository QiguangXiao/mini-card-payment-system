package com.minicard.authorization.infrastructure.delayjob;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import com.minicard.authorization.application.AuthorizationExpiryJobScheduler;
import com.minicard.authorization.domain.Authorization;
import com.minicard.delayjob.DelayJob;
import com.minicard.delayjob.DelayJobRepository;
import com.minicard.delayjob.DelayJobType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 把 authorization expiry plan 写入通用 delay_jobs 表。
 */
@Component
@RequiredArgsConstructor
public class AuthorizationExpiryDelayJobScheduler implements AuthorizationExpiryJobScheduler {

    // scheduler 和 handler 共享同一个 aggregateType 字符串 contract。
    // 如果这里写 Authorization 但 handler 期待 CardTransaction，job 会被正确拒绝而不是误执行。
    private static final String AGGREGATE_TYPE = "Authorization";

    private final DelayJobRepository delayJobRepository;
    private final Clock clock;

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
