package com.minicard.repayment.application;

import com.minicard.repayment.domain.event.RepaymentDomainEvent;

public interface RepaymentDomainEventPublisher {

    void append(RepaymentDomainEvent event);
}
