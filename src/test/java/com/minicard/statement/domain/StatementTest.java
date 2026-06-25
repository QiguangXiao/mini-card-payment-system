package com.minicard.statement.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

import com.minicard.authorization.domain.Money;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StatementTest {

    private static final Instant NOW = Instant.parse("2026-07-01T00:00:00Z");

    @Test
    void closesStatementWithSnapshotItems() {
        UUID accountId = UUID.randomUUID();

        Statement statement = Statement.close(
                accountId,
                LocalDate.parse("2026-06-01"),
                LocalDate.parse("2026-06-30"),
                LocalDate.parse("2026-07-25"),
                List.of(transaction("ntx-001", "1000.00"), transaction("ntx-002", "500.00")),
                money("1000.00"),
                NOW
        );

        assertThat(statement.status()).isEqualTo(StatementStatus.CLOSED);
        assertThat(statement.totalAmount().amount()).isEqualByComparingTo("1500.00");
        assertThat(statement.minimumPaymentAmount().amount()).isEqualByComparingTo("1000.00");
        assertThat(statement.paidAmount().amount()).isEqualByComparingTo("0.00");
        assertThat(statement.transactionCount()).isEqualTo(2);
        assertThat(statement.items())
                .hasSize(2)
                .allSatisfy(item -> assertThat(item.statementId()).isEqualTo(statement.id()));
    }

    @Test
    void rejectsMinimumPaymentAboveTotalAmount() {
        assertThatThrownBy(() -> Statement.close(
                UUID.randomUUID(),
                LocalDate.parse("2026-06-01"),
                LocalDate.parse("2026-06-30"),
                LocalDate.parse("2026-07-25"),
                List.of(transaction("ntx-001", "500.00")),
                money("1000.00"),
                NOW
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minimumPaymentAmount cannot exceed totalAmount");
    }

    @Test
    void rejectsTransactionOutsideBillingPeriod() {
        StatementLineSource transaction = new StatementLineSource(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "ntx-late",
                UUID.randomUUID(),
                "card-123",
                money("500.00"),
                Instant.parse("2026-07-01T00:00:00Z")
        );

        assertThatThrownBy(() -> Statement.close(
                UUID.randomUUID(),
                LocalDate.parse("2026-06-01"),
                LocalDate.parse("2026-06-30"),
                LocalDate.parse("2026-07-25"),
                List.of(transaction),
                money("500.00"),
                NOW
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside billing period");
    }

    @Test
    void appliesPartialAndFullRepayment() {
        Statement statement = statement("1500.00");

        statement.applyRepayment(money("500.00"), NOW.plusSeconds(1));

        assertThat(statement.status()).isEqualTo(StatementStatus.PARTIALLY_PAID);
        assertThat(statement.paidAmount().amount()).isEqualByComparingTo("500.00");
        assertThat(statement.remainingAmount().amount()).isEqualByComparingTo("1000.00");

        statement.applyRepayment(money("1000.00"), NOW.plusSeconds(2));

        assertThat(statement.status()).isEqualTo(StatementStatus.PAID);
        assertThat(statement.paidAmount().amount()).isEqualByComparingTo("1500.00");
        assertThat(statement.remainingAmount().amount()).isEqualByComparingTo("0.00");
    }

    @Test
    void rejectsRepaymentAboveRemainingAmount() {
        Statement statement = statement("500.00");

        assertThatThrownBy(() -> statement.applyRepayment(money("600.00"), NOW.plusSeconds(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exceeds statement remaining amount");
    }

    private StatementLineSource transaction(String networkTransactionId, String amount) {
        return new StatementLineSource(
                UUID.randomUUID(),
                UUID.randomUUID(),
                networkTransactionId,
                UUID.randomUUID(),
                "card-123",
                money(amount),
                Instant.parse("2026-06-15T10:00:00Z")
        );
    }

    private Statement statement(String amount) {
        return Statement.close(
                UUID.randomUUID(),
                LocalDate.parse("2026-06-01"),
                LocalDate.parse("2026-06-30"),
                LocalDate.parse("2026-07-25"),
                List.of(transaction("ntx-001", amount)),
                money(amount),
                NOW
        );
    }

    private Money money(String amount) {
        return new Money(new BigDecimal(amount), Currency.getInstance("JPY"));
    }
}
