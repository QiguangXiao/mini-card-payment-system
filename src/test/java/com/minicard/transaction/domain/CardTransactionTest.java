package com.minicard.transaction.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

import com.minicard.authorization.domain.Money;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CardTransactionTest {

    private static final Instant NOW = Instant.parse("2026-06-19T00:00:00Z");

    @Test
    void pendingTransactionCanBeMarkedPosted() {
        CardTransaction transaction = transaction();

        transaction.markPosted(NOW.plusSeconds(1));

        assertThat(transaction.status()).isEqualTo(CardTransactionStatus.POSTED);
        assertThat(transaction.postedAt()).isEqualTo(NOW.plusSeconds(1));
    }

    @Test
    void detectsSamePresentmentForIdempotentRetry() {
        CardTransaction transaction = transaction();

        assertThat(transaction.samePresentment(
                transaction.authorizationId(),
                money("100.00")
        )).isTrue();
    }

    private CardTransaction transaction() {
        return CardTransaction.pending(
                "ntx-001",
                UUID.randomUUID(),
                "card-123",
                UUID.randomUUID(),
                money("100.00"),
                NOW
        );
    }

    private Money money(String amount) {
        return new Money(new BigDecimal(amount), Currency.getInstance("JPY"));
    }
}
