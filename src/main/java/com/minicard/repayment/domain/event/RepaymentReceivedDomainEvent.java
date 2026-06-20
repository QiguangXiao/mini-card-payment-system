package com.minicard.repayment.domain.event;

import java.time.Instant;
import java.util.UUID;

import com.minicard.authorization.domain.Money;

public record RepaymentReceivedDomainEvent(
        UUID repaymentId,
        UUID statementId,
        UUID creditAccountId,
        Money amount,
        Money statementPaidAmount,
        Money statementRemainingAmount,
        Instant occurredAt
) implements RepaymentDomainEvent {
}
