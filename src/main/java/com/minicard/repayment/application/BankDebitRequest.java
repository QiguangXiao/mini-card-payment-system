package com.minicard.repayment.application;

import java.time.LocalDate;
import java.util.UUID;

import com.minicard.authorization.domain.Money;

public record BankDebitRequest(
        UUID statementId,
        UUID creditAccountId,
        Money amount,
        LocalDate dueDate
) {
}
