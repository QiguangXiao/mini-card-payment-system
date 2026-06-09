package com.minicard.authorization.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SingleTransactionLimitPolicyTest {

    private final SingleTransactionLimitPolicy policy = new SingleTransactionLimitPolicy(Map.of(
            Currency.getInstance("JPY"), new BigDecimal("100000.00")
    ));

    @Test
    void approvesAmountWithinLimit() {
        AuthorizationDecision decision = policy.decide(authorization("100000.00", "JPY"));

        assertThat(decision.approved()).isTrue();
        assertThat(decision.optionalDeclineReason()).isEmpty();
    }

    @Test
    void declinesAmountAboveLimit() {
        AuthorizationDecision decision = policy.decide(authorization("100000.01", "JPY"));

        assertThat(decision.approved()).isFalse();
        assertThat(decision.optionalDeclineReason())
                .contains(AuthorizationDeclineReason.SINGLE_TRANSACTION_LIMIT_EXCEEDED);
    }

    @Test
    void declinesUnsupportedCurrency() {
        AuthorizationDecision decision = policy.decide(authorization("10.00", "USD"));

        assertThat(decision.optionalDeclineReason())
                .contains(AuthorizationDeclineReason.UNSUPPORTED_CURRENCY);
    }

    private Authorization authorization(String amount, String currency) {
        return Authorization.request(
                "fingerprint-1",
                "card-123",
                new Money(new BigDecimal(amount), Currency.getInstance(currency)),
                Instant.parse("2026-06-07T00:00:00Z")
        );
    }
}
