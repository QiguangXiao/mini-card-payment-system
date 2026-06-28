package com.minicard.repayment.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.minicard.shared.domain.Money;
import com.minicard.repayment.domain.Repayment;
import com.minicard.statement.domain.Statement;
import com.minicard.statement.domain.StatementRepository;
import com.minicard.statement.domain.StatementLineSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AutoRepaymentServiceTest {

    private static final UUID ACCOUNT_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    private StatementRepository statementRepository;
    private BankDebitGateway bankDebitGateway;
    private RepaymentService repaymentService;
    private AutoRepaymentService service;

    @BeforeEach
    void setUp() {
        statementRepository = mock(StatementRepository.class);
        bankDebitGateway = mock(BankDebitGateway.class);
        repaymentService = mock(RepaymentService.class);
        service = new AutoRepaymentService(
                statementRepository,
                bankDebitGateway,
                repaymentService
        );
    }

    @Test
    void successfulBankDebitIsAppliedThroughRepaymentService() {
        Statement statement = statement();
        Repayment repayment = receivedRepayment(statement);
        when(statementRepository.findById(statement.id())).thenReturn(Optional.of(statement));
        when(bankDebitGateway.debit(any())).thenReturn(BankDebitResult.success());
        when(repaymentService.receive(any())).thenReturn(repayment);

        service.debitStatement(statement.id());

        // 银行扣款请求金额应等于账单剩余应还金额，并带上到期日用于对账。
        ArgumentCaptor<BankDebitRequest> debitRequest =
                ArgumentCaptor.forClass(BankDebitRequest.class);
        verify(bankDebitGateway).debit(debitRequest.capture());
        assertThat(debitRequest.getValue().amount().amount()).isEqualByComparingTo("1500.00");
        assertThat(debitRequest.getValue().dueDate()).isEqualTo(LocalDate.parse("2026-07-27"));
        // 银行扣款必须带 deterministic 幂等键，gateway 凭此让 DelayJob retry 不重复出金。
        assertThat(debitRequest.getValue().idempotencyKey())
                .isEqualTo("auto-debit:" + statement.id());
        // 扣款成功后必须经由 RepaymentService.receive 入账，并使用 deterministic 幂等键防止重复扣款。
        ArgumentCaptor<ReceiveRepaymentCommand> command =
                ArgumentCaptor.forClass(ReceiveRepaymentCommand.class);
        verify(repaymentService).receive(command.capture());
        assertThat(command.getValue().idempotencyKey())
                .isEqualTo("auto-debit:" + statement.id());
        assertThat(command.getValue().amount()).isEqualByComparingTo("1500.00");
    }

    @Test
    void failedBankDebitDoesNotApplyRepayment() {
        Statement statement = statement();
        when(statementRepository.findById(statement.id())).thenReturn(Optional.of(statement));
        when(bankDebitGateway.debit(any()))
                .thenReturn(BankDebitResult.failed("insufficient bank balance"));

        assertThatThrownBy(() -> service.debitStatement(statement.id()))
                .isInstanceOf(AutoRepaymentFailedException.class)
                .hasMessageContaining("insufficient bank balance");
        verify(repaymentService, never()).receive(any());
    }

    @Test
    void alreadyPaidStatementIsSkippedWithoutDebit() {
        // 账单已被手动还清后，到期 DelayJob 仍可能触发；此时应幂等跳过，既不扣款也不入账。
        Statement statement = statement();
        statement.applyRepayment(statement.totalAmount(), Instant.parse("2026-07-01T00:00:00Z"));
        when(statementRepository.findById(statement.id())).thenReturn(Optional.of(statement));

        service.debitStatement(statement.id());

        verify(bankDebitGateway, never()).debit(any());
        verify(repaymentService, never()).receive(any());
    }

    private Statement statement() {
        Statement statement = Statement.close(
                ACCOUNT_ID,
                LocalDate.parse("2026-05-16"),
                LocalDate.parse("2026-06-15"),
                LocalDate.parse("2026-07-27"),
                List.of(new StatementLineSource(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "ntx-001",
                        UUID.randomUUID(),
                        "card-123",
                        money("1500.00"),
                        Instant.parse("2026-06-01T10:00:00Z")
                )),
                money("1000.00"),
                Instant.parse("2026-06-16T00:00:00Z")
        );
        return statement;
    }

    private Repayment receivedRepayment(Statement statement) {
        Repayment repayment = Repayment.pending(
                "auto-debit:" + statement.id(),
                "fingerprint",
                statement.id(),
                statement.remainingAmount(),
                Instant.parse("2026-07-27T00:00:00Z")
        );
        repayment.markReceived(
                statement.creditAccountId(),
                statement.totalAmount(),
                money("0.00"),
                Instant.parse("2026-07-27T00:00:01Z")
        );
        repayment.pullDomainEvents();
        return repayment;
    }

    private Money money(String amount) {
        return new Money(new BigDecimal(amount), Currency.getInstance("JPY"));
    }
}
