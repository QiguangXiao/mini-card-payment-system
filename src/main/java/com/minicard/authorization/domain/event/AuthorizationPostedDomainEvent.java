package com.minicard.authorization.domain.event;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.minicard.authorization.domain.Money;

/**
 * Authorization 已被 presentment 入账的领域事件。
 *
 * <p>关键词：授权入账, presentment, 额度转实账, authorization posted,
 * presentment posting, posted transaction, オーソリ済み取引(オーソリずみとりひき),
 * 売上処理(うりあげしょり)。</p>
 */
public record AuthorizationPostedDomainEvent(
        /** 已入账的 authorization id。 */
        UUID authorizationId,
        /** 交易卡 id。 */
        String cardId,
        /** 原授权金额。 */
        Money requestedAmount,
        /** 入账事件发生时间。 */
        Instant occurredAt
) implements AuthorizationDomainEvent {

    public AuthorizationPostedDomainEvent {
        // posted 事件会经 Outbox 发布，必须保证 payload 完整。
        Objects.requireNonNull(authorizationId);
        Objects.requireNonNull(cardId);
        Objects.requireNonNull(requestedAmount);
        Objects.requireNonNull(occurredAt);
    }
}
