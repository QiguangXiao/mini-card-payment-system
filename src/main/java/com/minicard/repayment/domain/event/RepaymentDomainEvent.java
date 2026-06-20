package com.minicard.repayment.domain.event;

import java.time.Instant;
import java.util.UUID;

import com.minicard.authorization.domain.Money;

public interface RepaymentDomainEvent {

    UUID repaymentId();

    UUID statementId();

    UUID creditAccountId();

    Money amount();

    Instant occurredAt();
}
