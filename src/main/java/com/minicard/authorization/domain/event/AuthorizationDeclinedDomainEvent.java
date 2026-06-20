package com.minicard.authorization.domain.event;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.minicard.authorization.domain.AuthorizationDeclineReason;
import com.minicard.authorization.domain.Money;

/**
 * Authorization 被拒绝的领域事件。
 *
 * <p>关键词：授权拒绝, 拒绝原因, 风控结果, authorization declined,
 * decline reason, risk decision, オーソリ拒否(オーソリきょひ),
 * 拒否理由(きょひりゆう)。</p>
 */
public record AuthorizationDeclinedDomainEvent(
        /** 被拒绝的 authorization id。 */
        UUID authorizationId,
        /** 交易卡 id。 */
        String cardId,
        /** 请求授权金额。 */
        Money requestedAmount,
        /** 拒绝原因。 */
        AuthorizationDeclineReason declineReason,
        /** 事件发生时间。 */
        Instant occurredAt
) implements AuthorizationDomainEvent {

    public AuthorizationDeclinedDomainEvent {
        // 所有字段都是消费者理解拒绝原因的必要上下文。
        Objects.requireNonNull(authorizationId);
        Objects.requireNonNull(cardId);
        Objects.requireNonNull(requestedAmount);
        Objects.requireNonNull(declineReason);
        Objects.requireNonNull(occurredAt);
    }
}
