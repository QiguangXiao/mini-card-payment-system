package com.minicard.repayment.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.minicard.authorization.domain.Money;
import com.minicard.creditaccount.domain.CreditAccount;
import com.minicard.creditaccount.domain.CreditAccountRepository;
import com.minicard.creditaccount.domain.CreditAccountStatus;
import com.minicard.repayment.domain.Repayment;
import com.minicard.repayment.domain.RepaymentRepository;
import com.minicard.repayment.domain.RepaymentStatus;
import com.minicard.repayment.domain.event.RepaymentDomainEvent;
import com.minicard.repayment.domain.event.RepaymentReceivedDomainEvent;
import com.minicard.statement.domain.Statement;
import com.minicard.statement.domain.StatementRepository;
import com.minicard.statement.domain.StatementTransaction;
import com.minicard.statement.domain.StatementStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RepaymentServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-10T00:00:00Z");
    private static final UUID ACCOUNT_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    private RepaymentRepository repaymentRepository;
    private StatementRepository statementRepository;
    private CreditAccountRepository creditAccountRepository;
    private RepaymentDomainEventPublisher eventPublisher;
    private RepaymentService service;

    @BeforeEach
    void setUp() {
        repaymentRepository = mock(RepaymentRepository.class);
        statementRepository = mock(StatementRepository.class);
        creditAccountRepository = mock(CreditAccountRepository.class);
        eventPublisher = mock(RepaymentDomainEventPublisher.class);
        service = new RepaymentService(
                repaymentRepository,
                statementRepository,
                creditAccountRepository,
                eventPublisher,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void receivesRepaymentAndUpdatesStatementAndAccount() {
        ReceiveRepaymentCommand command = command("500.00");
        Statement statement = statement("1500.00");
        CreditAccount account = account("1500.00");
        arrangeNewRepayment();
        when(statementRepository.findById(command.statementId())).thenReturn(Optional.of(statement));
        when(creditAccountRepository.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(statementRepository.findByIdForUpdate(command.statementId())).thenReturn(Optional.of(statement));

        Repayment repayment = service.receive(command);

        assertThat(repayment.status()).isEqualTo(RepaymentStatus.RECEIVED);
        assertThat(repayment.creditAccountId()).contains(ACCOUNT_ID);
        assertThat(account.postedBalance().amount()).isEqualByComparingTo("1000.00");
        assertThat(statement.status()).isEqualTo(StatementStatus.PARTIALLY_PAID);
        assertThat(statement.paidAmount().amount()).isEqualByComparingTo("500.00");
        InOrder lockOrder = inOrder(statementRepository, creditAccountRepository);
        lockOrder.verify(statementRepository).findById(command.statementId());
        lockOrder.verify(creditAccountRepository).findByIdForUpdate(ACCOUNT_ID);
        lockOrder.verify(statementRepository).findByIdForUpdate(command.statementId());
        verify(creditAccountRepository).update(account);
        verify(statementRepository).updatePayment(statement);
        verify(repaymentRepository).update(repayment);
        ArgumentCaptor<RepaymentDomainEvent> event =
                ArgumentCaptor.forClass(RepaymentDomainEvent.class);
        verify(eventPublisher).append(event.capture());
        assertThat(event.getValue()).isInstanceOf(RepaymentReceivedDomainEvent.class);
    }

    @Test
    void returnsExistingRepaymentForIdempotentRetry() {
        ReceiveRepaymentCommand command = command("500.00");
        Repayment existing = receivedRepayment(command);
        when(statementRepository.findById(command.statementId())).thenReturn(Optional.of(statement("1500.00")));
        when(repaymentRepository.claim(any())).thenReturn(false);
        when(repaymentRepository.findByIdempotencyKeyForUpdate("rp-key-001"))
                .thenReturn(Optional.of(existing));

        Repayment result = service.receive(command);

        assertThat(result).isSameAs(existing);
        verify(statementRepository, never()).findByIdForUpdate(any());
        verify(creditAccountRepository, never()).findByIdForUpdate(any());
        verify(repaymentRepository, never()).update(any());
        verify(eventPublisher, never()).append(any());
    }

    @Test
    void rejectsRepaymentAboveStatementRemainingAmount() {
        ReceiveRepaymentCommand command = command("2000.00");
        Statement statement = statement("1500.00");
        CreditAccount account = account("3000.00");
        arrangeNewRepayment();
        when(statementRepository.findById(command.statementId())).thenReturn(Optional.of(statement));
        when(creditAccountRepository.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(statementRepository.findByIdForUpdate(command.statementId())).thenReturn(Optional.of(statement));

        assertThatThrownBy(() -> service.receive(command))
                .isInstanceOf(RepaymentRejectedException.class)
                .hasMessageContaining("exceeds statement remaining amount");
        verify(creditAccountRepository, never()).update(any());
        verify(statementRepository, never()).updatePayment(any());
        verify(eventPublisher, never()).append(any());
    }

    private void arrangeNewRepayment() {
        AtomicReference<Repayment> claimed = new AtomicReference<>();
        when(repaymentRepository.claim(any())).thenAnswer(invocation -> {
            claimed.set(invocation.getArgument(0));
            return true;
        });
        when(repaymentRepository.findByIdempotencyKeyForUpdate("rp-key-001"))
                .thenAnswer(invocation -> Optional.of(claimed.get()));
    }

    private ReceiveRepaymentCommand command(String amount) {
        return new ReceiveRepaymentCommand(
                "rp-key-001",
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                new BigDecimal(amount),
                Currency.getInstance("JPY")
        );
    }

    private Repayment receivedRepayment(ReceiveRepaymentCommand command) {
        Repayment repayment = Repayment.pending(
                command.idempotencyKey(),
                command.requestFingerprint(),
                command.statementId(),
                command.money(),
                NOW.minusSeconds(1)
        );
        repayment.markReceived(
                ACCOUNT_ID,
                money("500.00"),
                money("1000.00"),
                NOW
        );
        repayment.pullDomainEvents();
        return repayment;
    }

    private Statement statement(String amount) {
        Statement statement = Statement.close(
                ACCOUNT_ID,
                LocalDate.parse("2026-06-01"),
                LocalDate.parse("2026-06-30"),
                LocalDate.parse("2026-07-25"),
                List.of(new StatementTransaction(
                        UUID.randomUUID(),
                        "ntx-001",
                        UUID.randomUUID(),
                        "card-123",
                        money(amount),
                        Instant.parse("2026-06-15T10:00:00Z")
                )),
                money(amount),
                NOW.minusSeconds(10)
        );
        statement.pullDomainEvents();
        return statement;
    }

    private CreditAccount account(String postedBalance) {
        return CreditAccount.restore(
                ACCOUNT_ID,
                money("100000.00"),
                money("0.00"),
                money(postedBalance),
                CreditAccountStatus.ACTIVE
        );
    }

    private Money money(String amount) {
        return new Money(new BigDecimal(amount), Currency.getInstance("JPY"));
    }
}
