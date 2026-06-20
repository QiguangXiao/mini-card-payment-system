package com.minicard.repayment.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.minicard.repayment.domain.Repayment;

public record RepaymentResponse(
        UUID id,
        UUID statementId,
        UUID creditAccountId,
        BigDecimal amount,
        String currency,
        String status,
        Instant receivedAt,
        Instant createdAt
) {

    public static RepaymentResponse from(Repayment repayment) {
        return new RepaymentResponse(
                repayment.id(),
                repayment.statementId(),
                repayment.creditAccountId().orElse(null),
                repayment.amount().amount(),
                repayment.amount().currency().getCurrencyCode(),
                repayment.status().name(),
                repayment.receivedAt().orElse(null),
                repayment.createdAt()
        );
    }
}
