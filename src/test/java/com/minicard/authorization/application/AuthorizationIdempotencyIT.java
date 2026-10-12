package com.minicard.authorization.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.minicard.authorization.domain.Authorization;
import com.minicard.authorization.domain.AuthorizationRepository;
import com.minicard.authorization.domain.AuthorizationStatus;
import com.minicard.risk.application.RiskAssessmentService;
import com.minicard.risk.domain.RiskDecision;
import com.minicard.support.MySqlIntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 真 MySQL 验证同幂等键竞争、loser 共享锁读取和 rollback 后重新 claim。
 *
 * <p>关键词：幂等重放, 共享锁, 旧快照, idempotency, FOR SHARE, transaction rollback,
 * 共有ロック(きょうゆうロック), 冪等性(べきとうせい)。</p>
 *
 * <p>与不同 key 的额度并发测试互补：用 barrier 保证多个 loser 先同时持有重复索引 S 锁，
 * 再回读。若回读改回 FOR UPDATE，S -> X 升级循环就会让至少一个事务失败。</p>
 */
class AuthorizationIdempotencyIT extends MySqlIntegrationTestBase {

    private static final Currency JPY = Currency.getInstance("JPY");

    @Autowired
    private AuthorizationService service;
    @Autowired
    private AuthorizationRepository repository;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @MockitoBean
    private RiskAssessmentService risk;

    private String accountId;
    private String cardId;
    private String key;

    @BeforeEach
    void setUp() {
        when(risk.assess(any())).thenReturn(RiskDecision.approve(10));
        accountId = UUID.randomUUID().toString();
        cardId = "card-" + UUID.randomUUID();
        key = "idem-" + UUID.randomUUID();
        jdbc.update("INSERT INTO credit_accounts "
                        + "(id, credit_limit, reserved_amount, posted_balance, currency, status) "
                        + "VALUES (?, 10000.00, 0.00, 0.00, 'JPY', 'ACTIVE')", accountId);
        jdbc.update("INSERT INTO cards (id, credit_account_id, status) VALUES (?, ?, 'ACTIVE')",
                cardId, accountId);
    }

    @Test
    void concurrentSameKeyAuthorizationsReserveAndPublishOnlyOnce() throws Exception {
        int requests = 6;
        ExecutorService pool = Executors.newFixedThreadPool(requests);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Authorization>> results = new ArrayList<>();
        try {
            for (int i = 0; i < requests; i++) {
                results.add(pool.submit(() -> {
                    await(start);
                    return service.authorize(command("1000.00"));
                }));
            }
            start.countDown();
            Authorization first = results.getFirst().get(20, TimeUnit.SECONDS);
            UUID winnerId = first.id();
            for (Future<Authorization> result : results) {
                Authorization authorization = result.get(20, TimeUnit.SECONDS);
                assertThat(authorization.id()).isEqualTo(winnerId);
                assertThat(authorization.status()).isEqualTo(AuthorizationStatus.APPROVED);
                assertThat(authorization.createdAt()).isEqualTo(first.createdAt());
                assertThat(authorization.decidedAt()).isEqualTo(first.decidedAt());
                assertThat(authorization.expiresAt()).isEqualTo(first.expiresAt());
            }
            assertSingleApproval(winnerId);
            verify(risk, times(1)).assess(any());
        } finally {
            start.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void multipleLosersReadLatestResultWithoutUpgradingSharedLocks() throws Exception {
        int losers = 3;
        CountDownLatch snapshotsReady = new CountDownLatch(losers);
        CountDownLatch winnerCommitted = new CountDownLatch(1);
        CountDownLatch sharedLocksReady = new CountDownLatch(losers);
        ExecutorService pool = Executors.newFixedThreadPool(losers);
        List<Future<Authorization>> results = new ArrayList<>();
        try {
            for (int i = 0; i < losers; i++) {
                results.add(pool.submit(() -> repeatableReadTransaction().execute(status -> {
                    // 阶段 1：先建立“尚无 K”的 RR 快照。普通 SELECT 后续仍会看不到 winner；
                    // 只有 locking/current read 才能在同一事务读到新提交的结果。
                    assertThat(count("SELECT COUNT(*) FROM authorizations WHERE idempotency_key = ?", key))
                            .isZero();
                    snapshotsReady.countDown();
                    await(winnerCommitted);
                    AuthorizationCommand command = command("1000.00");
                    Authorization pending = Authorization.request(command.requestFingerprint(), cardId,
                            command.requestedAmount(), Instant.now());
                    assertThat(repository.claim(key, pending)).isFalse();
                    // 阶段 2：强制所有 loser 同时保留重复键 S 锁，再允许回读；
                    // 若省掉 barrier，偶然串行执行也会让错误的 FOR UPDATE 实现通过测试。
                    sharedLocksReady.countDown();
                    await(sharedLocksReady);
                    assertThat(count("SELECT COUNT(*) FROM authorizations WHERE idempotency_key = ?", key))
                            .isZero();
                    return repository.findByIdempotencyKeyForShare(key).orElseThrow();
                })));
            }
            await(snapshotsReady);
            Authorization winner = service.authorize(command("1000.00"));
            winnerCommitted.countDown();
            for (Future<Authorization> result : results) {
                Authorization replay = result.get(20, TimeUnit.SECONDS);
                assertThat(replay.id()).isEqualTo(winner.id());
                assertThat(replay.status()).isEqualTo(AuthorizationStatus.APPROVED);
                assertThat(replay.requestFingerprint()).isEqualTo(winner.requestFingerprint());
            }
            assertSingleApproval(winner.id());
            verify(risk, times(1)).assess(any());
        } finally {
            winnerCommitted.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void sameKeyWithDifferentPayloadRejectsWithoutAnotherReservation() {
        Authorization winner = service.authorize(command("1000.00"));
        assertThatThrownBy(() -> service.authorize(command("2000.00")))
                .isInstanceOf(IdempotencyConflictException.class);
        assertSingleApproval(winner.id());
        verify(risk, times(1)).assess(any());
    }

    @Test
    void rolledBackWinnerReleasesClaimAndAllBusinessWritesForRetry() {
        UUID[] rolledBackId = new UUID[1];
        // 外层事务让正常 authorize 加入同一 transaction boundary，批准后模拟提交前故障。
        // 如果 claim/account/job/outbox 有任何一个偷偷另开事务，下面的零残留断言会失败。
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).execute(status -> {
            rolledBackId[0] = service.authorize(command("1000.00")).id();
            throw new IllegalStateException("simulate failure before commit");
        })).isInstanceOf(IllegalStateException.class).hasMessage("simulate failure before commit");

        assertThat(count("SELECT COUNT(*) FROM authorizations WHERE idempotency_key = ?", key)).isZero();
        assertThat(count("SELECT COUNT(*) FROM outbox_events WHERE aggregate_id = ?",
                rolledBackId[0].toString())).isZero();
        assertThat(count("SELECT COUNT(*) FROM delay_jobs WHERE aggregate_id = ?",
                rolledBackId[0].toString())).isZero();
        assertThat(reserved()).isEqualByComparingTo("0.00");

        Authorization retry = service.authorize(command("1000.00"));
        assertThat(retry.id()).isNotEqualTo(rolledBackId[0]);
        assertSingleApproval(retry.id());
    }

    @Test
    void primaryKeyCollisionIsNotAnIdempotentLoser() {
        Authorization existing = service.authorize(command("1000.00"));
        // 真 Connector/J 错误格式也必须被正确识别；复用 ID、改用新 key 只能是 PRIMARY 冲突。
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).execute(status ->
                repository.claim("different-" + UUID.randomUUID(), existing)))
                .isInstanceOf(DuplicateKeyException.class);
        assertSingleApproval(existing.id());
    }

    private TransactionTemplate repeatableReadTransaction() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return transaction;
    }

    private AuthorizationCommand command(String amount) {
        return new AuthorizationCommand(key, cardId, new BigDecimal(amount), JPY,
                "merchant-1", "JP", "JP");
    }

    private void assertSingleApproval(UUID id) {
        assertThat(count("SELECT COUNT(*) FROM authorizations WHERE idempotency_key = ?", key))
                .isEqualTo(1);
        assertThat(reserved()).isEqualByComparingTo("1000.00");
        assertThat(repository.findById(id).orElseThrow().createdAt().getNano() % 1000).isZero();
        assertThat(count("SELECT COUNT(*) FROM outbox_events "
                + "WHERE aggregate_id = ? AND event_type = 'authorization.approved'", id.toString()))
                .isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM delay_jobs "
                + "WHERE aggregate_id = ? AND job_type = 'AUTHORIZATION_EXPIRY'", id.toString()))
                .isEqualTo(1);
    }

    private int count(String sql, String value) {
        return jdbc.queryForObject(sql, Integer.class, value);
    }

    private BigDecimal reserved() {
        return jdbc.queryForObject("SELECT reserved_amount FROM credit_accounts WHERE id = ?",
                BigDecimal.class, accountId);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting for test transaction barrier");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted test transaction barrier", exception);
        }
    }
}
