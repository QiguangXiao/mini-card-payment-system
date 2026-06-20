package com.minicard.repayment.domain.event;

import java.time.Instant;
import java.util.UUID;

import com.minicard.authorization.domain.Money;

/**
 * 还款已收到的领域事件。
 *
 * <p>关键词：还款入账, 账单余额, 领域事件, repayment received,
 * statement remaining amount, domain event, 入金受領(にゅうきんじゅりょう),
 * 請求残高(せいきゅうざんだか)。</p>
 *
 * <p>事件中同时带 paidAmount 和 remainingAmount，方便 notification/analytics 消费者不再反查 statement，
 * 也让 Outbox payload 能表达状态变化后的 snapshot。</p>
 */
public record RepaymentReceivedDomainEvent(
        /** 本次 repayment id。 */
        UUID repaymentId,
        /** 被还款的 statement id。 */
        UUID statementId,
        /** 还款所属 credit account id。 */
        UUID creditAccountId,
        /** 本次还款金额。 */
        Money amount,
        /** 入账后 statement 已还金额。 */
        Money statementPaidAmount,
        /** 入账后 statement 剩余应还金额。 */
        Money statementRemainingAmount,
        /** 事件发生时间。 */
        Instant occurredAt
) implements RepaymentDomainEvent {
}
