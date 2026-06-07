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
    void rejectsNonPositiveAmount() {
        assertThatThrownBy(() -> new Money(BigDecimal.ZERO, Currency.getInstance("JPY")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("amount must be greater than zero");
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
