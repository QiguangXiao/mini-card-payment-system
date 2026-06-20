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
import com.minicard.statement.domain.StatementRepository;
import com.minicard.statement.domain.event.StatementDomainEvent;
import com.minicard.statement.domain.event.StatementClosedDomainEvent;
import com.minicard.transaction.domain.CardTransaction;
import com.minicard.transaction.domain.CardTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StatementServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-01T00:00:00Z");
    private static final UUID ACCOUNT_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    private StatementRepository statementRepository;
    private CardTransactionRepository transactionRepository;
    private CreditAccountRepository creditAccountRepository;
    private StatementDomainEventPublisher eventPublisher;
    private StatementDueJobScheduler dueJobScheduler;
    private StatementService service;

    @BeforeEach
    void setUp() {
        statementRepository = mock(StatementRepository.class);
        transactionRepository = mock(CardTransactionRepository.class);
        creditAccountRepository = mock(CreditAccountRepository.class);
        eventPublisher = mock(StatementDomainEventPublisher.class);
        dueJobScheduler = mock(StatementDueJobScheduler.class);
        service = new StatementService(
                statementRepository,
                transactionRepository,
                creditAccountRepository,
                eventPublisher,
                dueJobScheduler,
                new StatementPolicyProperties(
                        new BigDecimal("0.10"),
                        Map.of("JPY", new BigDecimal("1000.00"))
                ),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void generatesStatementFromUnbilledPostedTransactions() {
        CreditAccount account = account();
        CardTransaction first = postedTransaction("ntx-001", "1000.00");
        CardTransaction second = postedTransaction("ntx-002", "500.00");
        when(creditAccountRepository.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(statementRepository.findByCycleForUpdate(
                ACCOUNT_ID,
                LocalDate.parse("2026-06-01"),
                LocalDate.parse("2026-06-30")
        )).thenReturn(Optional.empty());
        when(transactionRepository.findUnbilledPostedByCreditAccountForUpdate(
                eq(ACCOUNT_ID),
                eq(Instant.parse("2026-06-01T00:00:00Z")),
                eq(Instant.parse("2026-07-01T00:00:00Z"))
        )).thenReturn(List.of(first, second));
        when(statementRepository.insert(any())).thenReturn(true);

        Statement statement = service.generate(command());

        assertThat(statement.creditAccountId()).isEqualTo(ACCOUNT_ID);
        assertThat(statement.totalAmount().amount()).isEqualByComparingTo("1500.00");
        assertThat(statement.minimumPaymentAmount().amount()).isEqualByComparingTo("1000.00");
        assertThat(first.statementId()).contains(statement.id());
        assertThat(second.statementId()).contains(statement.id());
        verify(statementRepository).insert(statement);
        verify(transactionRepository).assignStatement(List.of(first, second));
        verify(dueJobScheduler).scheduleAutoRepayment(statement);
        ArgumentCaptor<StatementDomainEvent> event =
                ArgumentCaptor.forClass(StatementDomainEvent.class);
        verify(eventPublisher).append(event.capture());
        assertThat(event.getValue()).isInstanceOf(StatementClosedDomainEvent.class);
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
        verify(transactionRepository, never())
                .findUnbilledPostedByCreditAccountForUpdate(any(), any(), any());
        verify(statementRepository, never()).insert(any());
        verify(dueJobScheduler, never()).scheduleAutoRepayment(any());
        verify(eventPublisher, never()).append(any());
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
        when(transactionRepository.findUnbilledPostedByCreditAccountForUpdate(
                any(),
                any(),
                any()
        )).thenReturn(List.of());

        assertThatThrownBy(() -> service.generate(command()))
                .isInstanceOf(StatementGenerationRejectedException.class)
                .hasMessageContaining("no unbilled posted transactions");
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

    private CreditAccount account() {
        return CreditAccount.restore(
                ACCOUNT_ID,
                money("100000.00"),
                money("0.00"),
                money("1500.00"),
                CreditAccountStatus.ACTIVE
        );
    }

    private CardTransaction postedTransaction(String networkTransactionId, String amount) {
        CardTransaction transaction = CardTransaction.pending(
                networkTransactionId,
                UUID.randomUUID(),
                "card-123",
                ACCOUNT_ID,
                money(amount),
                Instant.parse("2026-06-15T10:00:00Z")
        );
        transaction.markPosted(Instant.parse("2026-06-15T10:00:01Z"));
        transaction.pullDomainEvents();
        return transaction;
    }

    private Statement existingStatement() {
        return Statement.close(
                ACCOUNT_ID,
                LocalDate.parse("2026-06-01"),
                LocalDate.parse("2026-06-30"),
                LocalDate.parse("2026-07-25"),
                List.of(new com.minicard.statement.domain.StatementTransaction(
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
