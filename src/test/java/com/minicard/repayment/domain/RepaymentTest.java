package com.minicard.repayment.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

import com.minicard.shared.domain.Money;
import com.minicard.repayment.domain.event.RepaymentReceivedDomainEvent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RepaymentTest {

    private static final Instant NOW = Instant.parse("2026-07-10T00:00:00Z");

    @Test
    void marksRepaymentReceivedAndEmitsDomainEvent() {
        UUID statementId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        Repayment repayment = Repayment.pending(
                "rp-key-001",
                "fingerprint",
                statementId,
                money("500.00"),
                NOW
        );

        repayment.markReceived(
                accountId,
                money("500.00"),
                money("1000.00"),
                NOW.plusSeconds(1)
        );

        assertThat(repayment.status()).isEqualTo(RepaymentStatus.RECEIVED);
        assertThat(repayment.creditAccountId()).contains(accountId);
        assertThat(repayment.receivedAt()).contains(NOW.plusSeconds(1));
        assertThat(repayment.pullDomainEvents())
                .hasSize(1)
                .first()
                .isInstanceOf(RepaymentReceivedDomainEvent.class);
    }

    private Money money(String amount) {
        return new Money(new BigDecimal(amount), Currency.getInstance("JPY"));
    }
}
