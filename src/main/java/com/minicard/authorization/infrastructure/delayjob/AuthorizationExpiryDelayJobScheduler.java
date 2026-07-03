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
 *
 * <p>关键词：授权过期, 延迟任务, 同事务计划, authorization expiry,
 * delay job, transaction boundary, オーソリ期限切れ(オーソリきげんぎれ),
 * 遅延ジョブ(ちえんジョブ)。</p>
 *
 * <p>它是 AuthorizationService 到 DelayJob 机制的 adapter：授权批准时只登记一个
 * future business action，真正释放预占额度由后台 DelayJob worker 到期后执行。</p>
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
        // 阶段 1：从已批准 authorization 取出过期时间。
        // APPROVED 没有 expiresAt 是领域错误；不提前失败会生成一个没有业务时间点的 DelayJob。
        Instant expiresAt = authorization.expiresAt()
                .orElseThrow(() -> new IllegalArgumentException(
                        "approved authorization must have expiresAt"
                ));
        // 阶段 2：记录创建时间。使用注入 Clock，测试能固定时间，生产也避免散落 Instant.now()。
        Instant now = Instant.now(clock);

        // 阶段 3：写入 AUTHORIZATION_EXPIRY DelayJob。
        // insertIfAbsent() 和 AuthorizationService 的 APPROVED 写入同事务。
        // 如果 approval rollback，延迟释放计划也 rollback，保持 authorization/delay_jobs 一致。
        // 如果同一个 authorization 因重试再次调度，unique job contract 会让 insertIfAbsent 成为 no-op。
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
