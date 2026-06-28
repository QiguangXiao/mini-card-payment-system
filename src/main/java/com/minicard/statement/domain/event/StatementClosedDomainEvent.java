package com.minicard.statement.domain.event;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.minicard.shared.domain.Money;

/**
 * 账单已关账（生成）的领域事件。
 *
 * <p>关键词：账单生成, 出账, 最低还款, 到期日, statement closed,
 * minimum payment, due date, domain event, 締め確定(しめかくてい),
 * 支払期日(しはらいきじつ)。</p>
 *
 * <p>事件携带 total/minimum/dueDate 这层 snapshot，让 notification、PDF 生成、还款提醒等消费者
 * 不必反查 statement 表就能渲染“本期账单已生成”。和 RepaymentReceivedDomainEvent 一样，
 * 这些是关账时刻的不可变快照，而不是实时余额视图。</p>
 */
public record StatementClosedDomainEvent(
        /** 本期账单 id。 */
        UUID statementId,
        /** 账单所属信用账户 id（partition key / recipientKey）。 */
        UUID creditAccountId,
        /** 账期开始日（含）。 */
        LocalDate periodStart,
        /** 账期结束日（含，即关账日）。 */
        LocalDate periodEnd,
        /** 还款到期日。 */
        LocalDate dueDate,
        /** 账单应还总额。 */
        Money totalAmount,
        /** 本期最低还款额。 */
        Money minimumPaymentAmount,
        /** 账单包含的交易明细笔数。 */
        int transactionCount,
        /** 事件发生时间（账单生成时刻）。 */
        Instant occurredAt
) implements StatementDomainEvent {
}
