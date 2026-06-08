package com.minicard.authorization.domain;

import java.math.BigDecimal;
import java.util.Currency;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    @Test
    void normalizesAmountScale() {
        Money money = new Money(new BigDecimal("100"), Currency.getInstance("JPY"));

        assertThat(money.amount()).isEqualByComparingTo("100.00");
    }

    @Test
    void allowsZeroForBalances() {
        Money money = new Money(BigDecimal.ZERO, Currency.getInstance("JPY"));

        assertThat(money.amount()).isEqualByComparingTo("0.00");
    }

    @Test
    void rejectsNegativeAmount() {
        assertThatThrownBy(() -> new Money(new BigDecimal("-0.01"), Currency.getInstance("JPY")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("amount must not be negative");
    }

    @Test
    void rejectsAmountWithMoreThanTwoDecimalPlaces() {
        assertThatThrownBy(() -> new Money(
                new BigDecimal("10.001"),
                Currency.getInstance("USD")
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("amount must have at most 2 decimal places");
    }
}
