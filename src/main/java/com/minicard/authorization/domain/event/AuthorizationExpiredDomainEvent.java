package com.minicard.authorization.domain.event;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.minicard.authorization.domain.Money;

/**
 * Authorization 过期的领域事件。
 *
 * <p>关键词：授权过期, 额度释放, 延迟任务, authorization expired,
 * authorization release, delay job, オーソリ期限切れ(オーソリきげんぎれ),
 * オーソリのリリース(オーソリのリリース)。</p>
 */
public record AuthorizationExpiredDomainEvent(
        /** 过期的 authorization id。 */
        UUID authorizationId,
        /** 交易卡 id。 */
        String cardId,
        /** 原授权金额。 */
        Money requestedAmount,
        /** 原计划过期时间。 */
        Instant expiresAt,
        /** 实际处理过期的时间。 */
        Instant occurredAt
) implements AuthorizationDomainEvent {

    public AuthorizationExpiredDomainEvent {
        // 过期事件会触发通知/审计，缺字段应在创建时 fail fast。
        Objects.requireNonNull(authorizationId);
        Objects.requireNonNull(cardId);
        Objects.requireNonNull(requestedAmount);
        Objects.requireNonNull(expiresAt);
        Objects.requireNonNull(occurredAt);
    }
}
