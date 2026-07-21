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

import com.minicard.shared.domain.Money;
import com.minicard.creditaccount.domain.CreditAccount;
import com.minicard.creditaccount.domain.CreditAccountRepository;
import com.minicard.creditaccount.domain.CreditAccountStatus;
import com.minicard.repayment.domain.Repayment;
import com.minicard.repayment.domain.RepaymentRepository;
import com.minicard.repayment.domain.RepaymentStatus;
import com.minicard.statement.domain.Statement;
import com.minicard.statement.domain.StatementRepository;
import com.minicard.statement.domain.StatementLineSource;
import com.minicard.statement.domain.StatementStatus;
import com.minicard.statement.application.read.StatementReadService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Repayment 写路径的幂等、锁顺序和金额状态测试。
 *
 * <p>关键词：还款入账, 幂等 claim, 锁后重校验, repayment service,
 * row lock, after-commit eviction, 入金処理(にゅうきんしょり)。</p>
 *
 * <p>核心不变式是同一还款只减少一次 posted balance，并在锁住 account/statement 后用最新 remaining
 * amount 再校验。缓存断言只验证注册 after-commit eviction，不能替代 StatementReadService 的缓存竞态测试。</p>
 */
class RepaymentServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-10T00:00:00Z");
    private static final UUID ACCOUNT_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    private RepaymentRepository repaymentRepository;
    private StatementRepository statementRepository;
    private CreditAccountRepository creditAccountRepository;
    private StatementReadService statementReadService;
    private RepaymentService service;

    @BeforeEach
    void setUp() {
        repaymentRepository = mock(RepaymentRepository.class);
        statementRepository = mock(StatementRepository.class);
        creditAccountRepository = mock(CreditAccountRepository.class);
        statementReadService = mock(StatementReadService.class);
        service = new RepaymentService(
                repaymentRepository,
                statementRepository,
                creditAccountRepository,
                statementReadService,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    // 测试目的：验证还款 happy path 同事务更新 repayment、statement、credit account 和 cache invalidation。
    // variant：部分还款 500/1500，statement 变 PARTIALLY_PAID，account postedBalance 下降。
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
        assertThat(statement.version()).isEqualTo(1);
        InOrder lockOrder = inOrder(statementRepository, creditAccountRepository);
        lockOrder.verify(statementRepository).findById(command.statementId());
        lockOrder.verify(creditAccountRepository).findByIdForUpdate(ACCOUNT_ID);
        lockOrder.verify(statementRepository).findByIdForUpdate(command.statementId());
        verify(creditAccountRepository).update(account);
        verify(statementRepository).updatePayment(statement);
        verify(statementReadService).evictAfterCommit(statement);
        verify(repaymentRepository).update(repayment);
    }

    @Test
    // 测试目的：验证同 idempotency key 的还款重试返回第一次 RECEIVED 结果。
    // variant：claim=false 代表 duplicate loser，不再锁 statement/account，也不重复执行入账。
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
        verify(statementReadService, never()).evictAfterCommit(any());
    }

    @Test
    // 测试目的：验证锁内重新校验 remaining amount，阻止超额还款。
    // variant：请求 2000 但 statement remaining 只有 1500，拒绝且不更新 account/statement。
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
        repayment.markReceived(ACCOUNT_ID, NOW);
        return repayment;
    }

    private Statement statement(String amount) {
        return Statement.close(
                ACCOUNT_ID,
                LocalDate.parse("2026-06-01"),
                LocalDate.parse("2026-06-30"),
                LocalDate.parse("2026-07-25"),
                List.of(new StatementLineSource(
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
