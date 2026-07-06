package com.minicard.statement.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

import com.minicard.shared.domain.Money;
import com.minicard.statement.domain.event.StatementClosedDomainEvent;
import com.minicard.statement.domain.event.StatementDomainEvent;
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
        assertThat(statement.version()).isZero();
        assertThat(statement.items())
                .hasSize(2)
                .allSatisfy(item -> assertThat(item.statementId()).isEqualTo(statement.id()));
    }

    @Test
    void recordsStatementClosedEventOnceAfterClose() {
        UUID accountId = UUID.randomUUID();

        Statement statement = Statement.close(
                accountId,
                LocalDate.parse("2026-06-01"),
                LocalDate.parse("2026-06-30"),
                LocalDate.parse("2026-07-27"),
                List.of(transaction("ntx-001", "1000.00"), transaction("ntx-002", "500.00")),
                money("1000.00"),
                NOW
        );

        List<StatementDomainEvent> events = statement.pullDomainEvents();

        assertThat(events).singleElement()
                .isInstanceOfSatisfying(StatementClosedDomainEvent.class, closed -> {
                    assertThat(closed.statementId()).isEqualTo(statement.id());
                    assertThat(closed.creditAccountId()).isEqualTo(accountId);
                    assertThat(closed.dueDate()).isEqualTo(LocalDate.parse("2026-07-27"));
                    assertThat(closed.totalAmount().amount()).isEqualByComparingTo("1500.00");
                    assertThat(closed.minimumPaymentAmount().amount()).isEqualByComparingTo("1000.00");
                    assertThat(closed.transactionCount()).isEqualTo(2);
                    assertThat(closed.occurredAt()).isEqualTo(NOW);
                });
        // pull 后清空：reload/二次 pull 不会让同一张账单重复发布 statement.closed。
        assertThat(statement.pullDomainEvents()).isEmpty();
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
    void acceptsTransactionOnPeriodStartInBillingTimezone() {
        StatementLineSource transaction = transactionAt(
                "ntx-jst-start",
                "500.00",
                // UTC では 5/31 だが、JST の請求日切では 6/1 00:30。
                // 旧実装の UTC 判定だとここを誤って outside billing period として拒否していた。
                Instant.parse("2026-05-31T15:30:00Z")
        );

        Statement statement = Statement.close(
                UUID.randomUUID(),
                LocalDate.parse("2026-06-01"),
                LocalDate.parse("2026-06-30"),
                LocalDate.parse("2026-07-25"),
                List.of(transaction),
                money("500.00"),
                NOW
        );

        assertThat(statement.items()).singleElement()
                .satisfies(line -> assertThat(line.networkTransactionId()).isEqualTo("ntx-jst-start"));
    }

    @Test
    void appliesPartialAndFullRepayment() {
        Statement statement = statement("1500.00");

        statement.applyRepayment(money("500.00"), NOW.plusSeconds(1));

        assertThat(statement.status()).isEqualTo(StatementStatus.PARTIALLY_PAID);
        assertThat(statement.paidAmount().amount()).isEqualByComparingTo("500.00");
        assertThat(statement.remainingAmount().amount()).isEqualByComparingTo("1000.00");
        assertThat(statement.version()).isEqualTo(1);

        statement.applyRepayment(money("1000.00"), NOW.plusSeconds(2));

        assertThat(statement.status()).isEqualTo(StatementStatus.PAID);
        assertThat(statement.paidAmount().amount()).isEqualByComparingTo("1500.00");
        assertThat(statement.remainingAmount().amount()).isEqualByComparingTo("0.00");
        assertThat(statement.version()).isEqualTo(2);
    }

    @Test
    void rejectsRepaymentAboveRemainingAmount() {
        Statement statement = statement("500.00");

        assertThatThrownBy(() -> statement.applyRepayment(money("600.00"), NOW.plusSeconds(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exceeds statement remaining amount");
    }

    @Test
    void snapshotAssignsOwnIdentityAndKeepsSourceFactIds() {
        UUID statementId = UUID.randomUUID();
        StatementLineSource source = transaction("ntx-snap", "800.00");

        StatementLine line = StatementLine.snapshot(statementId, source, NOW);

        // 快照行有自己的身份，不复用来源事实的 id；否则交易生命周期和账单快照生命周期会混在一起。
        assertThat(line.id())
                .isNotEqualTo(source.cardTransactionId())
                .isNotEqualTo(source.ledgerEntryId());
        assertThat(line.statementId()).isEqualTo(statementId);
        assertThat(line.cardTransactionId()).isEqualTo(source.cardTransactionId());
        // snapshot 工厂契约：新出账的 line 必须能追到 ledger entry；只有 restore 容忍历史数据的 null。
        assertThat(line.ledgerEntryId()).contains(source.ledgerEntryId());
        assertThat(line.createdAt()).isEqualTo(NOW);
    }

    private StatementLineSource transaction(String networkTransactionId, String amount) {
        return transactionAt(networkTransactionId, amount, Instant.parse("2026-06-15T10:00:00Z"));
    }

    private StatementLineSource transactionAt(
            String networkTransactionId,
            String amount,
            Instant postedAt
    ) {
        return new StatementLineSource(
                UUID.randomUUID(),
                UUID.randomUUID(),
                networkTransactionId,
                UUID.randomUUID(),
                "card-123",
                money(amount),
                postedAt
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
