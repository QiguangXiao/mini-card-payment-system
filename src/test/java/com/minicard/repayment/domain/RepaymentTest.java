package com.minicard.repayment.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

import com.minicard.shared.domain.Money;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RepaymentTest {

    private static final Instant NOW = Instant.parse("2026-07-10T00:00:00Z");

    @Test
    void marksRepaymentReceived() {
        UUID statementId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        Repayment repayment = Repayment.pending(
                "rp-key-001",
                "fingerprint",
                statementId,
                money("500.00"),
                NOW
        );

        repayment.markReceived(accountId, NOW.plusSeconds(1));

        assertThat(repayment.status()).isEqualTo(RepaymentStatus.RECEIVED);
        assertThat(repayment.creditAccountId()).contains(accountId);
        assertThat(repayment.receivedAt()).contains(NOW.plusSeconds(1));
    }

    private Money money(String amount) {
        return new Money(new BigDecimal(amount), Currency.getInstance("JPY"));
    }
}
