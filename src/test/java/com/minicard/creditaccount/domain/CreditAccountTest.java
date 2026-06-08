package com.minicard.creditaccount.domain;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.UUID;

import com.minicard.authorization.domain.Money;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CreditAccountTest {

    @Test
    void reservesAvailableCredit() {
        CreditAccount account = account("1000.00", "200.00", CreditAccountStatus.ACTIVE);

        CreditReservationResult result = account.reserve(money("300.00"));

        assertThat(result.reserved()).isTrue();
        assertThat(account.reservedAmount().amount()).isEqualByComparingTo("500.00");
        assertThat(account.availableCredit().amount()).isEqualByComparingTo("500.00");
    }

    @Test
    void rejectsReservationExceedingAvailableCredit() {
        CreditAccount account = account("1000.00", "950.00", CreditAccountStatus.ACTIVE);

        CreditReservationResult result = account.reserve(money("100.00"));

        assertThat(result.optionalFailure())
                .contains(CreditReservationFailure.INSUFFICIENT_AVAILABLE_CREDIT);
        assertThat(account.reservedAmount().amount()).isEqualByComparingTo("950.00");
    }

    @Test
    void rejectsReservationForBlockedAccount() {
        CreditAccount account = account("1000.00", "0.00", CreditAccountStatus.BLOCKED);

        CreditReservationResult result = account.reserve(money("100.00"));

        assertThat(result.optionalFailure()).contains(CreditReservationFailure.ACCOUNT_BLOCKED);
    }

    @Test
    void rejectsReservationInDifferentCurrency() {
        CreditAccount account = account("1000.00", "0.00", CreditAccountStatus.ACTIVE);
        Money usd = new Money(new BigDecimal("100.00"), Currency.getInstance("USD"));

        CreditReservationResult result = account.reserve(usd);

        assertThat(result.optionalFailure()).contains(CreditReservationFailure.CURRENCY_MISMATCH);
    }

    private CreditAccount account(String limit, String reserved, CreditAccountStatus status) {
        return CreditAccount.restore(
                UUID.randomUUID(),
                money(limit),
                money(reserved),
                status
        );
    }

    private Money money(String amount) {
        return new Money(new BigDecimal(amount), Currency.getInstance("JPY"));
    }
}
