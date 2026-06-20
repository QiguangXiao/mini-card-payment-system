package com.minicard.transaction.domain.event;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.minicard.authorization.domain.Money;

/**
 * CardTransaction 已入账的领域事件。
 *
 * <p>关键词：交易入账, presentment, 账单候选, card transaction posted,
 * presentment posting, statement candidate, 売上処理(うりあげしょり),
 * 請求対象取引(せいきゅうたいしょうとりひき)。</p>
 */
public record CardTransactionPostedDomainEvent(
        /** 入账交易 id。 */
        UUID cardTransactionId,
        /** 外部网络交易 id。 */
        String networkTransactionId,
        /** 对应 authorization id。 */
        UUID authorizationId,
        /** card id。 */
        String cardId,
        /** credit account id。 */
        UUID creditAccountId,
        /** 入账金额。 */
        Money amount,
        /** 事件发生时间。 */
        Instant occurredAt
) implements CardTransactionDomainEvent {

    public CardTransactionPostedDomainEvent {
        // Outbox payload 必须完整，否则 notification/statement projection 无法可靠消费。
        Objects.requireNonNull(cardTransactionId);
        Objects.requireNonNull(networkTransactionId);
        Objects.requireNonNull(authorizationId);
        Objects.requireNonNull(cardId);
        Objects.requireNonNull(creditAccountId);
        Objects.requireNonNull(amount);
        Objects.requireNonNull(occurredAt);
    }
}
