package com.minicard.repayment.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.minicard.authorization.application.AuthorizationCommand;
import com.minicard.authorization.application.AuthorizationService;
import com.minicard.authorization.domain.Authorization;
import com.minicard.repayment.domain.Repayment;
import com.minicard.repayment.domain.RepaymentStatus;
import com.minicard.risk.application.RiskAssessmentService;
import com.minicard.risk.domain.RiskDecision;
import com.minicard.statement.application.GenerateStatementCommand;
import com.minicard.statement.application.StatementGenerationService;
import com.minicard.statement.application.read.StatementReadService;
import com.minicard.statement.domain.Statement;
import com.minicard.statement.infrastructure.mybatis.MyBatisStatementBillingRepository;
import com.minicard.support.MySqlIntegrationTestBase;
import com.minicard.transaction.application.PostPresentmentCommand;
import com.minicard.transaction.application.PostingService;
import com.minicard.transaction.application.PresentmentConflictException;
import com.minicard.transaction.domain.CardTransaction;
import com.minicard.transaction.infrastructure.mybatis.MyBatisCardTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 真 MySQL 验证还款外键锁顺序、posting 缺失键查重和出账/重试交错。
 *
 * <p>关键词：外键共享锁, 间隙锁, 还款并发, FK lock, gap lock, idempotency,
 * 外部キー(がいぶキー), 並行制御(へいこうせいぎょ)。</p>
 *
 * <p>只替换外部风控和缓存协作者，账户、账单、交易、还款、Outbox 和 DelayJob 均真实落库。
 * barrier 强制多个新 posting 都查不到记录后才推进；若首次查重取得 gap lock，后续 INSERT 会互相等待。</p>
 */
class FinancialWriteConcurrencyIT extends MySqlIntegrationTestBase {

    private static final Instant NOW = Instant.parse("2026-10-12T00:00:00.123456789Z");
    private static final Currency JPY = Currency.getInstance("JPY");

    @Autowired private AuthorizationService authorizations;
    @Autowired private PostingService posting;
    @Autowired private RepaymentService repayments;
    @Autowired private StatementGenerationService statements;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoBean private RiskAssessmentService risk;
    @MockitoBean private StatementReadService statementReadService;
    @MockitoBean private Clock clock;
    @MockitoSpyBean private MyBatisCardTransactionRepository transactions;
    @MockitoSpyBean private MyBatisStatementBillingRepository billing;

    private Account fixture;
    private Statement statement;

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(NOW);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        when(risk.assess(any())).thenReturn(RiskDecision.approve(10));
        fixture = newAccount();
        Authorization authorization = authorize(fixture, "20000");
        posting.post(presentment(authorization, "fixture-" + UUID.randomUUID()));
        statement = statements.generate(cycle(fixture));
    }

    @Test
    void distinctKeysOnSameStatementDoNotDeadlockOrLosePayments() throws Exception {
        int requests = 6;
        List<Repayment> results = concurrent(requests, index ->
                repayments.receive(repayment("different-" + index + "-" + UUID.randomUUID(), "1000")));
        assertThat(results).allMatch(result -> result.status() == RepaymentStatus.RECEIVED);
        assertBalances("6000", "14000", requests);
        assertThat(count("SELECT COUNT(*) FROM repayments WHERE statement_id = ?", statement.id().toString()))
                .isEqualTo(requests);
        verify(statementReadService, times(requests)).evictAfterCommit(any());
    }

    @Test
    void fullRepaymentRetriesReturnOriginalResultEvenAfterStatementIsPaid() throws Exception {
        String key = "full-" + UUID.randomUUID();
        List<Repayment> results = concurrent(6, index -> repayments.receive(repayment(key, "20000")));
        UUID firstId = results.getFirst().id();
        assertThat(results).allMatch(result -> result.id().equals(firstId));
        assertThat(results).allMatch(result -> result.createdAt().equals(NOW.truncatedTo(java.time.temporal.ChronoUnit.MICROS)));
        assertBalances("20000", "0", 1);
        assertThat(count("SELECT COUNT(*) FROM repayments WHERE idempotency_key = ?", key)).isEqualTo(1);
        verify(statementReadService, times(1)).evictAfterCommit(any());
    }

    @Test
    void concurrentOverpaymentsAreRejectedWithoutPartialWrites() throws Exception {
        List<Repayment> results = concurrent(4, index -> {
            try {
                return repayments.receive(repayment("overpay-" + index + "-" + UUID.randomUUID(), "10000"));
            } catch (RepaymentRejectedException expected) {
                return null;
            }
        });
        assertThat(results.stream().filter(java.util.Objects::nonNull).count()).isEqualTo(2);
        assertBalances("20000", "0", 2);
        assertThat(count("SELECT COUNT(*) FROM repayments WHERE statement_id = ?", statement.id().toString()))
                .isEqualTo(2);
    }

    @Test
    void conflictingPayloadDoesNotApplyAnotherPayment() {
        String key = "conflict-" + UUID.randomUUID();
        repayments.receive(repayment(key, "1000"));
        assertThatThrownBy(() -> repayments.receive(repayment(key, "2000")))
                .isInstanceOf(RepaymentConflictException.class);
        assertBalances("1000", "19000", 1);
    }

    @Test
    void rollbackRemovesClaimAndBalanceChangesThenRetrySucceeds() {
        String key = "rollback-" + UUID.randomUUID();
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).execute(status -> {
            repayments.receive(repayment(key, "1000"));
            throw new IllegalStateException("fail before commit");
        })).isInstanceOf(IllegalStateException.class).hasMessage("fail before commit");
        assertBalances("0", "20000", 0);
        assertThat(count("SELECT COUNT(*) FROM repayments WHERE idempotency_key = ?", key)).isZero();
        repayments.receive(repayment(key, "1000"));
        assertBalances("1000", "19000", 1);
    }

    @Test
    void foreignKeyStillRejectsOrphanRepayment() {
        // 真实 schema 保留外键：脚本绕过 service 也不能写不存在的 statement，不能靠注释声称保护仍在。
        assertThatThrownBy(() -> jdbc.update("INSERT INTO repayments "
                        + "(id, idempotency_key, request_fingerprint, statement_id, amount, currency, status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 1000, 'JPY', 'PENDING', ?, ?)",
                UUID.randomUUID().toString(), "orphan-" + UUID.randomUUID(), "f".repeat(64),
                UUID.randomUUID().toString(), java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertBalances("0", "20000", 0);
    }

    @Test
    void missingNetworkKeysDoNotHoldGapsBeforeAccountGate() throws Exception {
        Account account = newAccount();
        List<Authorization> holds = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            holds.add(authorize(account, "1000"));
        }
        String prefix = "zz-gap-" + UUID.randomUUID() + "-";
        CountDownLatch lookups = new CountDownLatch(holds.size());
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            // 强制所有请求完成未命中查重后再去争 account；不同 network key 都落在索引最末间隙。
            lookups.countDown();
            await(lookups);
            return result;
        }).when(transactions).findByNetworkTransactionId(startsWith(prefix));

        List<CardTransaction> results = concurrent(holds.size(), index ->
                posting.post(presentment(holds.get(index), prefix + index)));
        assertThat(results).hasSize(holds.size());
        assertThat(balance(account, "reserved_amount")).isEqualByComparingTo("0");
        assertThat(balance(account, "posted_balance")).isEqualByComparingTo("6000");
        assertThat(count("SELECT COUNT(*) FROM card_transactions WHERE credit_account_id = ?", account.id().toString()))
                .isEqualTo(6);
        assertThat(count("SELECT COUNT(*) FROM outbox_events WHERE event_type = 'card_transaction.posted' "
                + "AND aggregate_id IN (SELECT id FROM card_transactions WHERE credit_account_id = ?)", account.id().toString()))
                .isEqualTo(6);
    }

    @Test
    void concurrentDuplicatePostingsMoveHoldAndPublishOnlyOnce() throws Exception {
        Account account = newAccount();
        Authorization authorization = authorize(account, "1000");
        String networkId = "retry-" + UUID.randomUUID();
        PostPresentmentCommand command = presentment(authorization, networkId);
        List<CardTransaction> results = concurrent(6, index -> posting.post(command));
        CardTransaction first = results.getFirst();
        assertThat(results).allMatch(result -> result.id().equals(first.id()));
        assertThat(results).allMatch(result -> result.createdAt().equals(first.createdAt()));
        assertThat(results).allMatch(result -> result.postedAt().equals(first.postedAt()));
        assertThat(balance(account, "reserved_amount")).isEqualByComparingTo("0");
        assertThat(balance(account, "posted_balance")).isEqualByComparingTo("1000");
        assertThat(count("SELECT COUNT(*) FROM card_transactions WHERE network_transaction_id = ?", networkId))
                .isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM outbox_events WHERE aggregate_id = ? "
                + "AND event_type = 'card_transaction.posted'", first.id().toString())).isEqualTo(1);
    }

    @Test
    void sameNetworkKeyAcrossAccountsHasOneWinnerAndSharedReadConflicts() throws Exception {
        List<Account> accounts = new ArrayList<>();
        List<Authorization> holds = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Account account = newAccount();
            accounts.add(account);
            holds.add(authorize(account, "1000"));
        }
        String networkId = "cross-account-" + UUID.randomUUID();
        CountDownLatch lookups = new CountDownLatch(holds.size());
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            // 所有请求先建立“networkId 尚不存在”的 RR 快照，随后才 INSERT。
            // loser 必须 FOR SHARE 读最新 winner，否则旧快照会误报 row not visible。
            lookups.countDown();
            await(lookups);
            return result;
        }).when(transactions).findByNetworkTransactionId(networkId);

        List<CardTransaction> results = concurrent(holds.size(), index -> {
            try {
                return posting.post(presentment(holds.get(index), networkId));
            } catch (PresentmentConflictException expected) {
                return null;
            }
        });
        List<CardTransaction> winners = results.stream().filter(java.util.Objects::nonNull).toList();
        assertThat(winners).hasSize(1);
        CardTransaction winner = winners.getFirst();
        for (int i = 0; i < accounts.size(); i++) {
            boolean won = holds.get(i).id().equals(winner.authorizationId());
            assertThat(balance(accounts.get(i), "posted_balance")).isEqualByComparingTo(won ? "1000" : "0");
            assertThat(balance(accounts.get(i), "reserved_amount")).isEqualByComparingTo(won ? "0" : "1000");
        }
        assertThat(count("SELECT COUNT(*) FROM card_transactions WHERE network_transaction_id = ?", networkId))
                .isEqualTo(1);
    }

    @Test
    void duplicatePostingCanFinishWhileBillingHoldsTransactionLock() throws Exception {
        Account account = newAccount();
        Authorization authorization = authorize(account, "1000");
        PostPresentmentCommand command = presentment(authorization, "billing-race-" + UUID.randomUUID());
        CardTransaction original = posting.post(command);
        CountDownLatch billingLocked = new CountDownLatch(1);
        CountDownLatch replayFinished = new CountDownLatch(1);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            // 出账已持有 account/transaction X，下一步 statement_lines FK 还要引用 authorization。
            // duplicate 持 authorization X 后若再等 transaction X，会形成隐藏 FK 锁循环。
            billingLocked.countDown();
            await(replayFinished);
            return result;
        }).when(billing).findBillableLineSourcesForUpdate(eq(account.id()), any(), any());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Statement> generated = pool.submit(() -> statements.generate(cycle(account)));
            await(billingLocked);
            Future<CardTransaction> replay = pool.submit(() -> posting.post(command));
            assertThat(replay.get(5, TimeUnit.SECONDS).id()).isEqualTo(original.id());
            replayFinished.countDown();
            assertThat(generated.get(15, TimeUnit.SECONDS).totalAmount().amount()).isEqualByComparingTo("1000");
            assertThat(balance(account, "posted_balance")).isEqualByComparingTo("1000");
        } finally {
            replayFinished.countDown();
            pool.shutdownNow();
        }
    }

    private Account newAccount() {
        Account account = new Account(UUID.randomUUID(), "card-" + UUID.randomUUID());
        jdbc.update("INSERT INTO credit_accounts (id, credit_limit, reserved_amount, posted_balance, currency, status) "
                + "VALUES (?, 100000, 0, 0, 'JPY', 'ACTIVE')", account.id().toString());
        jdbc.update("INSERT INTO cards (id, credit_account_id, status) VALUES (?, ?, 'ACTIVE')",
                account.cardId(), account.id().toString());
        return account;
    }

    private Authorization authorize(Account account, String amount) {
        return authorizations.authorize(new AuthorizationCommand("auth-" + UUID.randomUUID(), account.cardId(),
                new BigDecimal(amount), JPY, "merchant-1", "JP", "JP"));
    }

    private PostPresentmentCommand presentment(Authorization authorization, String networkId) {
        return new PostPresentmentCommand(networkId, authorization.id(), authorization.requestedAmount().amount(), JPY);
    }

    private GenerateStatementCommand cycle(Account account) {
        return new GenerateStatementCommand(account.id(), LocalDate.parse("2026-10-01"),
                LocalDate.parse("2026-10-31"), LocalDate.parse("2026-11-25"));
    }

    private ReceiveRepaymentCommand repayment(String key, String amount) {
        return new ReceiveRepaymentCommand(key, statement.id(), new BigDecimal(amount), JPY);
    }

    private void assertBalances(String paid, String posted, int version) {
        assertThat(jdbc.queryForObject("SELECT paid_amount FROM statements WHERE id = ?", BigDecimal.class,
                statement.id().toString())).isEqualByComparingTo(paid);
        assertThat(balance(fixture, "posted_balance")).isEqualByComparingTo(posted);
        assertThat(jdbc.queryForObject("SELECT version FROM statements WHERE id = ?", Long.class,
                statement.id().toString())).isEqualTo((long) version);
    }

    private BigDecimal balance(Account account, String column) {
        // column 仅来自本测试固定常量，不接收外部输入；业务 SQL 使用 mapper bind parameters。
        return jdbc.queryForObject("SELECT " + column + " FROM credit_accounts WHERE id = ?",
                BigDecimal.class, account.id().toString());
    }

    private int count(String sql, String value) {
        return jdbc.queryForObject(sql, Integer.class, value);
    }

    private <T> List<T> concurrent(int count, java.util.function.IntFunction<T> operation) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(count);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < count; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    await(start);
                    return operation.apply(index);
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(20, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            start.countDown();
            pool.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting for concurrency barrier");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted concurrency barrier", exception);
        }
    }

    private record Account(UUID id, String cardId) {
    }
}
