package com.minicard.transaction.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

import com.minicard.shared.domain.Money;
import com.minicard.transaction.domain.event.CardTransactionPostedDomainEvent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CardTransactionTest {

    private static final Instant NOW = Instant.parse("2026-06-19T00:00:00Z");

    @Test
    void pendingTransactionCanBeMarkedPosted() {
        CardTransaction transaction = transaction();

        transaction.markPosted(NOW.plusSeconds(1));

        assertThat(transaction.status()).isEqualTo(CardTransactionStatus.POSTED);
        assertThat(transaction.billingStatus()).isEqualTo(CardTransactionBillingStatus.UNBILLED);
        assertThat(transaction.postedAt()).isEqualTo(NOW.plusSeconds(1));
        assertThat(transaction.pullDomainEvents())
                .hasSize(1)
                .first()
                .isInstanceOf(CardTransactionPostedDomainEvent.class);
    }

    @Test
    void detectsSamePresentmentForIdempotentRetry() {
        CardTransaction transaction = transaction();

        assertThat(transaction.samePresentment(
                transaction.authorizationId(),
                money("100.00")
        )).isTrue();
    }

    @Test
    void postedTransactionCanBeAssignedToStatementOnce() {
        CardTransaction transaction = transaction();
        transaction.markPosted(NOW.plusSeconds(1));
        UUID statementId = UUID.randomUUID();

        transaction.assignToStatement(statementId, NOW.plusSeconds(2));

        assertThat(transaction.statementId()).contains(statementId);
        assertThat(transaction.billingStatus()).isEqualTo(CardTransactionBillingStatus.BILLED);
        assertThat(transaction.statementAssignedAt()).contains(NOW.plusSeconds(2));
        assertThatThrownBy(() -> transaction.assignToStatement(UUID.randomUUID(), NOW.plusSeconds(3)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already assigned");
    }

    @Test
    void pendingTransactionCannotBeAssignedToStatement() {
        CardTransaction transaction = transaction();

        assertThatThrownBy(() -> transaction.assignToStatement(UUID.randomUUID(), NOW.plusSeconds(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("only posted");
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
