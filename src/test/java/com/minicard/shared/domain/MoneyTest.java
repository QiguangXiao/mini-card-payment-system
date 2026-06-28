package com.minicard.shared.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    private static final Currency JPY = Currency.getInstance("JPY");
    private static final Currency USD = Currency.getInstance("USD");

    @Test
    void normalizesToCurrencyFractionDigits() {
        // JPY 是零小数币种：scale 归一到 0；USD 归一到 2。
        assertThat(new Money(new BigDecimal("100"), JPY).amount().scale()).isZero();
        assertThat(new Money(new BigDecimal("100"), USD).amount().scale()).isEqualTo(2);
    }

    @Test
    void acceptsTrailingZeroDowncast() {
        // DB 里 JPY 金额存成 DECIMAL(19,2) → 100000.00；尾零可安全降级到 100000，不应报错。
        Money money = new Money(new BigDecimal("100000.00"), JPY);

        assertThat(money.amount()).isEqualByComparingTo("100000");
        assertThat(money.amount().scale()).isZero();
    }

    @Test
    void allowsZeroForBalances() {
        assertThat(new Money(BigDecimal.ZERO, JPY).amount()).isEqualByComparingTo("0");
    }

    @Test
    void rejectsNegativeAmount() {
        assertThatThrownBy(() -> new Money(new BigDecimal("-1"), JPY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("amount must not be negative");
    }

    @Test
    void rejectsFractionalYen() {
        // JPY 没有“分以下日元”；真正丢精度的输入必须 fail fast，而不是被静默取整。
        assertThatThrownBy(() -> new Money(new BigDecimal("1234.50"), JPY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds JPY precision");
    }

    @Test
    void rejectsAmountBeyondCurrencyPrecision() {
        assertThatThrownBy(() -> new Money(new BigDecimal("10.001"), USD))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds USD precision");
    }

    @Test
    void multiplyRoundsAtCurrencyScale() {
        // 最低还款这类比例计算：JPY 向上取整到整数日元，不产生小数日元。
        Money minimum = new Money(new BigDecimal("12345"), JPY)
                .multiply(new BigDecimal("0.10"), RoundingMode.CEILING);

        assertThat(minimum.amount()).isEqualByComparingTo("1235");
        assertThat(minimum.amount().scale()).isZero();
    }
}
