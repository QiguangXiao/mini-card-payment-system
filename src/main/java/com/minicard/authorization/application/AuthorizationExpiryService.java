package com.minicard.authorization.application;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import com.minicard.authorization.domain.Authorization;
import com.minicard.authorization.domain.AuthorizationRepository;
import com.minicard.authorization.domain.event.AuthorizationDomainEvent;
import com.minicard.card.domain.Card;
import com.minicard.card.domain.CardRepository;
import com.minicard.creditaccount.domain.CreditAccount;
import com.minicard.creditaccount.domain.CreditAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 处理单个过期授权(expired authorization)，并在同一事务内释放 reserved credit。
 *
 * <p>一个 job 一个 transaction，可以缩短 DB lock 时间，也避免坏数据回滚同一轮里的其他 job。</p>
 *
 * <p>流程总览（mini trace，一个 job 一个 DB transaction）：</p>
 * <pre>
 * DelayJob AUTHORIZATION_EXPIRY 到期触发（expiresAt 已过）
 *  -> SELECT authorization FOR UPDATE（业务表才是 source of truth，job 只是执行计划）
 *  -> 非 APPROVED: 幂等 skip（已 posted/expired，retry/手工重放安全）
 *  -> now 仍早于 expiresAt: 抛异常（job 早跑是 bug，不能提前释放）
 *  -> load card（不锁）-> creditAccountId
 *  -> SELECT credit_account FOR UPDATE（与新授权 reserve 串行化）
 *  -> account.release(reserved amount)
 *  -> authorization APPROVED -> EXPIRED
 *  -> append Outbox event authorization.expired
 *  -> COMMIT（release/EXPIRED/event 三件事同事务）
 * </pre>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AuthorizationExpiryService {

    private final AuthorizationRepository authorizationRepository;
    private final CardRepository cardRepository;
    private final CreditAccountRepository creditAccountRepository;
    private final AuthorizationDomainEventPublisher eventPublisher;
    private final Clock clock;

    /**
     * 执行授权过期 job：锁定授权和账户，释放额度，并发布 authorization.expired。
     */
    // 每个 expiry job 独立开事务。这样一条坏 job 失败不会回滚同一轮 worker 里的其他 job。
    @Transactional
    public void expire(UUID authorizationId) {
        Instant now = Instant.now(clock);
        // 先锁 authorization row：delay_jobs 只是执行计划，真正能否释放额度要看业务表 source of truth。
        Authorization authorization = authorizationRepository
                .findByIdForUpdate(authorizationId)
                .orElseThrow(() -> new IllegalStateException(
                        "authorization expiry job references missing authorization "
                                + authorizationId
                ));

        if (!authorization.status().isApproved()) {
            // 状态已经不是 APPROVED 时，说明不再需要 expiry；直接视为成功，保证 retry/manual replay 幂等。
            log.info(
                    "authorization_expiry_skipped authorizationId={} status={}",
                    authorization.id(),
                    authorization.status()
            );
            return;
        }
        Instant expiresAt = authorization.expiresAt()
                .orElseThrow(() -> new IllegalStateException(
                        "approved authorization has no expiresAt"
                ));
        if (now.isBefore(expiresAt)) {
            throw new IllegalStateException("authorization expiry job ran before expiresAt");
        }

        // Card 用于找到 creditAccountId；authorization 保存 cardId，不直接保存 accountId，保持边界清晰。
        Card card = cardRepository.findById(authorization.cardId())
                .orElseThrow(() -> new IllegalStateException(
                        "approved authorization references missing card "
                                + authorization.cardId()
                ));
        // account row lock 把 expiry release 和新 authorization reserve 串行化，
        // 避免并发请求基于 stale reservedAmount 计算可用额度。
        CreditAccount account = creditAccountRepository.findByIdForUpdate(card.creditAccountId())
                .orElseThrow(() -> new IllegalStateException(
                        "approved authorization references missing credit account "
                                + card.creditAccountId()
                ));

        account.release(authorization.requestedAmount());
        authorization.expire(now);

        // 三件事同事务提交：account release、authorization EXPIRED、Outbox expired event。
        // 任何一步失败都会 rollback，避免释放额度但状态/事件缺失。
        creditAccountRepository.update(account);
        authorizationRepository.update(authorization);
        publishDomainEvents(authorization);
        log.info(
                "authorization_expired authorizationId={} accountId={} amount={} currency={}",
                authorization.id(),
                account.id(),
                authorization.requestedAmount().amount(),
                authorization.requestedAmount().currency().getCurrencyCode()
        );
    }

    /**
     * 把 authorization.expired 领域事件追加到 Outbox。
     *
     * <p>事务归属：只由 {@link #expire(UUID)} 调用，加入同一个 {@code @Transactional}
     * 边界；Outbox row 必须和 account release、authorization EXPIRED 状态一起提交。</p>
     */
    private void publishDomainEvents(Authorization authorization) {
        for (AuthorizationDomainEvent event : authorization.pullDomainEvents()) {
            // expire() 产生业务事实，Outbox adapter 负责 durable publish intent。
            // 这样过期状态和 expired event 仍处在同一个 transaction boundary。
            eventPublisher.append(event);
        }
    }
}
