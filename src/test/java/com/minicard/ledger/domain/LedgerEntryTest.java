package com.minicard.ledger.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

import com.minicard.shared.domain.Money;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LedgerEntryTest {

    private static final Instant NOW = Instant.parse("2026-07-01T00:00:00Z");
    private static final Currency JPY = Currency.getInstance("JPY");

    @Test
    void purchasePostedCreatesDebitEntry() {
        UUID eventId = UUID.randomUUID();
        UUID cardTransactionId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();

        LedgerEntry entry = LedgerEntry.recordPurchasePosted(
                eventId,
                cardTransactionId,
                accountId,
                new Money(new BigDecimal("1000.00"), JPY),
                NOW,
                NOW.plusSeconds(1)
        );

        assertThat(entry.sourceEventId()).isEqualTo(eventId);
        assertThat(entry.entryType()).isEqualTo(LedgerEntryType.CARD_TRANSACTION_POSTED);
        assertThat(entry.direction()).isEqualTo(LedgerDirection.DEBIT);
        assertThat(entry.sourceType()).isEqualTo(LedgerSourceType.CARD_TRANSACTION);
        assertThat(entry.sourceId()).isEqualTo(cardTransactionId);
        assertThat(entry.creditAccountId()).isEqualTo(accountId);
    }

    @Test
    void repaymentReceivedCreatesCreditEntry() {
        UUID eventId = UUID.randomUUID();
        UUID repaymentId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();

        LedgerEntry entry = LedgerEntry.recordRepaymentReceived(
                eventId,
                repaymentId,
                accountId,
                new Money(new BigDecimal("500.00"), JPY),
                NOW,
                NOW.plusSeconds(1)
        );

        assertThat(entry.sourceEventId()).isEqualTo(eventId);
        assertThat(entry.entryType()).isEqualTo(LedgerEntryType.REPAYMENT_RECEIVED);
        assertThat(entry.direction()).isEqualTo(LedgerDirection.CREDIT);
        assertThat(entry.sourceType()).isEqualTo(LedgerSourceType.REPAYMENT);
        assertThat(entry.sourceId()).isEqualTo(repaymentId);
        assertThat(entry.creditAccountId()).isEqualTo(accountId);
    }

    @Test
    void zeroAmountIsRejected() {
        assertThatThrownBy(() -> LedgerEntry.recordPurchasePosted(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                new Money(BigDecimal.ZERO, JPY),
                NOW,
                NOW
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ledger entry amount");
    }
}
