package com.minicard.authorization.domain.event;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.minicard.authorization.domain.Money;

/**
 * Authorization 被批准的领域事件。
 *
 * <p>关键词：授权批准, 额度占用, 过期时间, authorization approved,
 * amount hold, expiry, オーソリ承認(オーソリしょうにん),
 * 金額の確保(きんがくのかくほ)。</p>
 */
public record AuthorizationApprovedDomainEvent(
        /** 被批准的 authorization id。 */
        UUID authorizationId,
        /** 交易卡 id。 */
        String cardId,
        /** 请求授权金额。 */
        Money requestedAmount,
        /** 事件发生时间。 */
        Instant occurredAt,
        /** 授权保留额度的过期时间。 */
        Instant expiresAt
) implements AuthorizationDomainEvent {

    public AuthorizationApprovedDomainEvent {
        // record compact constructor 防御空值，避免无效事件进入 Outbox。
        Objects.requireNonNull(authorizationId);
        Objects.requireNonNull(cardId);
        Objects.requireNonNull(requestedAmount);
        Objects.requireNonNull(occurredAt);
        Objects.requireNonNull(expiresAt);
    }
}
