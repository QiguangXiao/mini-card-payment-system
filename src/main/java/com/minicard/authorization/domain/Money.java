package com.minicard.authorization.domain;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;

public record Money(BigDecimal amount, Currency currency) {

    public Money {
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be greater than zero");
        }
        if (amount.scale() > 2) {
            throw new IllegalArgumentException("amount must have at most 2 decimal places");
        }

        // The current database model supports two decimal places. A production
        // multi-currency system should define scale rules per supported currency.
        amount = amount.setScale(2);
    }

    public boolean isGreaterThan(Money other) {
        ensureSameCurrency(other);
        return amount.compareTo(other.amount) > 0;
    }

    private void ensureSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException("money currencies must match");
        }
    }
}
