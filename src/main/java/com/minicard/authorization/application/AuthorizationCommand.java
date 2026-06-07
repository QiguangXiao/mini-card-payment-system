package com.minicard.authorization.application;

import java.math.BigDecimal;
import java.util.Currency;

import com.minicard.authorization.domain.Authorization;
import com.minicard.authorization.domain.Money;

public record AuthorizationCommand(
        String idempotencyKey,
        String cardId,
        BigDecimal amount,
        Currency currency
) {

    public Money requestedAmount() {
        return new Money(amount, currency);
    }

    public boolean matches(Authorization authorization) {
        return cardId.equals(authorization.cardId())
                && requestedAmount().equals(authorization.requestedAmount());
    }
}
