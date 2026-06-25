package com.minicard.statement.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.minicard.authorization.domain.Money;
import com.minicard.creditaccount.domain.CreditAccount;
import com.minicard.creditaccount.domain.CreditAccountRepository;
import com.minicard.creditaccount.domain.CreditAccountStatus;
import com.minicard.statement.domain.Statement;
import com.minicard.statement.domain.StatementLineSource;
import com.minicard.statement.domain.StatementRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StatementGenerationServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-01T00:00:00Z");
    private static final UUID ACCOUNT_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    private StatementRepository statementRepository;
    private StatementBillingRepository billingRepository;
    private CreditAccountRepository creditAccountRepository;
    private StatementDueJobScheduler dueJobScheduler;
    private StatementGenerationService service;

    @BeforeEach
    void setUp() {
        statementRepository = mock(StatementRepository.class);
        billingRepository = mock(StatementBillingRepository.class);
        creditAccountRepository = mock(CreditAccountRepository.class);
        dueJobScheduler = mock(StatementDueJobScheduler.class);
        service = new StatementGenerationService(
                statementRepository,
                billingRepository,
                creditAccountRepository,
                dueJobScheduler,
                statementProperties(),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void generatesStatementFromUnbilledPostedTransactions() {
        CreditAccount account = account();
        StatementLineSource first = lineSource("ntx-001", "1000.00");
        StatementLineSource second = lineSource("ntx-002", "500.00");
        when(creditAccountRepository.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(statementRepository.findByCycleForUpdate(
                ACCOUNT_ID,
                LocalDate.parse("2026-06-01"),
                LocalDate.parse("2026-06-30")
        )).thenReturn(Optional.empty());
        when(billingRepository.existsUnbilledPostedTransactionMissingLedger(
                eq(ACCOUNT_ID),
                eq(Instant.parse("2026-05-31T15:00:00Z")),
                eq(Instant.parse("2026-06-30T15:00:00Z"))
        )).thenReturn(false);
        when(billingRepository.findBillableLineSourcesForUpdate(
                eq(ACCOUNT_ID),
                eq(Instant.parse("2026-05-31T15:00:00Z")),
                eq(Instant.parse("2026-06-30T15:00:00Z"))
        )).thenReturn(List.of(first, second));
        when(statementRepository.insert(any())).thenReturn(true);

        Statement statement = service.generate(command());

        assertThat(statement.creditAccountId()).isEqualTo(ACCOUNT_ID);
        assertThat(statement.totalAmount().amount()).isEqualByComparingTo("1500.00");
        assertThat(statement.minimumPaymentAmount().amount()).isEqualByComparingTo("1000.00");
        assertThat(statement.items())
                .extracting(item -> item.ledgerEntryId().orElseThrow())
                .containsExactly(first.ledgerEntryId(), second.ledgerEntryId());
        verify(statementRepository).insert(statement);
        verify(billingRepository).markCardTransactionsBilled(statement.id(), List.of(first, second), NOW);
        verify(dueJobScheduler).scheduleAutoRepayment(statement);
    }

    @Test
    void returnsExistingStatementForSameBillingCycle() {
        CreditAccount account = account();
        Statement existing = existingStatement();
        when(creditAccountRepository.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(statementRepository.findByCycleForUpdate(
                ACCOUNT_ID,
                LocalDate.parse("2026-06-01"),
                LocalDate.parse("2026-06-30")
        )).thenReturn(Optional.of(existing));

        Statement result = service.generate(command());

        assertThat(result).isSameAs(existing);
        verify(billingRepository, never())
                .findBillableLineSourcesForUpdate(any(), any(), any());
        verify(statementRepository, never()).insert(any());
        verify(dueJobScheduler, never()).scheduleAutoRepayment(any());
    }

    @Test
    void rejectsGenerationWhenNoUnbilledPostedTransactionsExist() {
        CreditAccount account = account();
        when(creditAccountRepository.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(statementRepository.findByCycleForUpdate(
                ACCOUNT_ID,
                LocalDate.parse("2026-06-01"),
                LocalDate.parse("2026-06-30")
        )).thenReturn(Optional.empty());
        when(billingRepository.existsUnbilledPostedTransactionMissingLedger(
                any(),
                any(),
                any()
        )).thenReturn(false);
        when(billingRepository.findBillableLineSourcesForUpdate(
                any(),
                any(),
                any()
        )).thenReturn(List.of());

        assertThatThrownBy(() -> service.generate(command()))
                .isInstanceOf(StatementGenerationException.class)
                .hasMessageContaining("no unbilled posted transactions");
        verify(statementRepository, never()).insert(any());
        verify(dueJobScheduler, never()).scheduleAutoRepayment(any());
    }

    @Test
    void retriesWhenPostedTransactionsAreWaitingForLedgerEntries() {
        CreditAccount account = account();
        when(creditAccountRepository.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(statementRepository.findByCycleForUpdate(
                ACCOUNT_ID,
                LocalDate.parse("2026-06-01"),
                LocalDate.parse("2026-06-30")
        )).thenReturn(Optional.empty());
        when(billingRepository.existsUnbilledPostedTransactionMissingLedger(
                any(),
                any(),
                any()
        )).thenReturn(true);

        assertThatThrownBy(() -> service.generate(command()))
                .isInstanceOf(StatementGenerationException.class)
                .hasMessageContaining("waiting for ledger entries");
        verify(statementRepository, never()).insert(any());
        verify(dueJobScheduler, never()).scheduleAutoRepayment(any());
    }

    private GenerateStatementCommand command() {
        return new GenerateStatementCommand(
                ACCOUNT_ID,
                LocalDate.parse("2026-06-01"),
                LocalDate.parse("2026-06-30"),
                LocalDate.parse("2026-07-25")
        );
    }

    private StatementProperties statementProperties() {
        return new StatementProperties(
                new StatementProperties.Batch(true, "0 0 1 * * *", "Asia/Tokyo", 31, 27, 1000),
                new StatementProperties.Jobs(true, 1000, 10000, 10, 3, 300, 4, 20),
                new StatementProperties.Policy(
                        new BigDecimal("0.10"),
                        Map.of("JPY", new BigDecimal("1000.00"))
                )
        );
    }

    private CreditAccount account() {
        return CreditAccount.restore(
                ACCOUNT_ID,
                money("100000.00"),
                money("0.00"),
                money("1500.00"),
                CreditAccountStatus.ACTIVE
        );
    }

    private StatementLineSource lineSource(String networkTransactionId, String amount) {
        return new StatementLineSource(
                UUID.randomUUID(),
                UUID.randomUUID(),
                networkTransactionId,
                UUID.randomUUID(),
                "card-123",
                money(amount),
                Instant.parse("2026-06-15T10:00:01Z")
        );
    }

    private Statement existingStatement() {
        return Statement.close(
                ACCOUNT_ID,
                LocalDate.parse("2026-06-01"),
                LocalDate.parse("2026-06-30"),
                LocalDate.parse("2026-07-25"),
                List.of(new StatementLineSource(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "ntx-existing",
                        UUID.randomUUID(),
                        "card-123",
                        money("500.00"),
                        Instant.parse("2026-06-15T10:00:01Z")
                )),
                money("500.00"),
                NOW
        );
    }

    private Money money(String amount) {
        return new Money(new BigDecimal(amount), Currency.getInstance("JPY"));
    }
}
