package com.minicard.authorization.application;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.minicard.authorization.domain.Authorization;
import com.minicard.authorization.domain.AuthorizationStatus;
import com.minicard.risk.application.RiskAssessmentService;
import com.minicard.risk.domain.RiskDecision;
import com.minicard.support.MySqlIntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 授权并发正确性测试：N 个线程同时授权同一个 account，断言“绝不超额授权 + reserved 最终一致”。
 *
 * <p>关键词：并发授权, 超额授权, 行锁, lost update, concurrent authorization,
 * over-authorization, FOR UPDATE, オーソリ並行制御, 二重与信防止。</p>
 *
 * <p>这是整个系统最重要的正确性命题。之前只有 {@code AuthorizationServiceTest}（mock 单测）
 * 在“假设 repository 串行”的前提下验证 reserve 逻辑——它无法证明并发下行锁真的生效。
 * 这里用真 MySQL + 真事务 + 真 {@code SELECT ... FOR UPDATE} 把命题钉死：</p>
 *
 * <ul>
 *   <li>额度 10000，每笔预占 1000，{@value THREADS} 个线程并发抢 → 恰好 10 笔批准；</li>
 *   <li>最终 {@code reserved_amount} 必须等于 批准数 × 1000（不多不少）；</li>
 *   <li>任何线程都不应抛异常（被拒是业务结果，不是错误）。</li>
 * </ul>
 *
 * <p>反事实：如果把 mapper 的 {@code FOR UPDATE} 去掉，多个线程会读到同一个 availableCredit，
 * 各自 reserve 后写回——出现 lost update：批准数 × 1000 &gt; reserved_amount，此断言立刻失败。
 * 这正是“我怎么知道我的锁是对的”这个 interview 问题的可运行答案。</p>
 */
class AuthorizationConcurrencyIT extends MySqlIntegrationTestBase {

    private static final Currency JPY = Currency.getInstance("JPY");
    private static final int THREADS = 24;
    private static final BigDecimal CREDIT_LIMIT = new BigDecimal("10000.00");
    private static final BigDecimal PER_AUTHORIZATION = new BigDecimal("1000.00");
    // 额度刚好够 10 笔；其余请求必须被干净地拒绝，而不是把账户带进超额状态。
    private static final int EXPECTED_APPROVED = 10;

    @Autowired
    private AuthorizationService authorizationService;

    @Autowired
    private JdbcTemplate jdbc;

    // 风控走 Feign 外部调用，在 webEnvironment=NONE 下会 fail-closed 拒绝；这里替换成永远批准的 mock，
    // 让测试聚焦于唯一被验证对象：额度并发控制。velocity/外部风控不是本测试关心的变量。
    @MockitoBean
    private RiskAssessmentService riskAssessmentService;

    private String accountId;
    private String cardId;

    @BeforeEach
    void setUp() {
        when(riskAssessmentService.assess(any())).thenReturn(RiskDecision.approve(10));
        // 每次用全新 UUID，避免上一轮残留 row 干扰；account 先于 card 插入（cards 有 FK 指向 accounts）。
        accountId = UUID.randomUUID().toString();
        cardId = "card-" + UUID.randomUUID();
        jdbc.update(
                "INSERT INTO credit_accounts (id, credit_limit, reserved_amount, posted_balance, currency, status) "
                        + "VALUES (?, ?, 0.00, 0.00, 'JPY', 'ACTIVE')",
                accountId, CREDIT_LIMIT);
        jdbc.update(
                "INSERT INTO cards (id, credit_account_id, status) VALUES (?, ?, 'ACTIVE')",
                cardId, accountId);
    }

    @Test
    void concurrentAuthorizationsNeverOverReserveSameAccount() throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        // startGate 让所有线程在同一瞬间放开，最大化对同一 account row 的真实争用。
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(THREADS);
        List<AuthorizationStatus> outcomes = new CopyOnWriteArrayList<>();
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < THREADS; i++) {
            int index = i;
            pool.submit(() -> {
                try {
                    startGate.await();
                    AuthorizationCommand command = new AuthorizationCommand(
                            "idem-" + index + "-" + UUID.randomUUID(),
                            cardId,
                            PER_AUTHORIZATION,
                            JPY,
                            "merchant-1",
                            "JP",
                            "JP");
                    Authorization result = authorizationService.authorize(command);
                    outcomes.add(result.status());
                } catch (Throwable t) {
                    // 被拒是 DECLINED 状态而非异常；任何异常都代表锁/事务/约束出了问题，必须暴露。
                    failures.add(t);
                } finally {
                    finished.countDown();
                }
            });
        }

        startGate.countDown();
        boolean completed = finished.await(60, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertThat(completed).as("all authorization threads finished within timeout").isTrue();
        assertThat(failures)
                .as("no thread threw — a declined authorization is a normal business outcome, not an error")
                .isEmpty();

        long approved = outcomes.stream().filter(AuthorizationStatus.APPROVED::equals).count();
        long declined = outcomes.stream().filter(AuthorizationStatus.DECLINED::equals).count();

        assertThat(approved)
                .as("exactly %d authorizations fit within the 10000 limit at 1000 each", EXPECTED_APPROVED)
                .isEqualTo(EXPECTED_APPROVED);
        assertThat(declined).isEqualTo(THREADS - EXPECTED_APPROVED);

        BigDecimal reserved = jdbc.queryForObject(
                "SELECT reserved_amount FROM credit_accounts WHERE id = ?", BigDecimal.class, accountId);
        // 核心不变式：reserved 必须正好等于 批准数 × 单笔金额。若行锁失效出现 lost update，这里会对不上。
        assertThat(reserved)
                .as("reserved must equal approvedCount * perAuth — no over-reservation, no lost update")
                .isEqualByComparingTo(PER_AUTHORIZATION.multiply(BigDecimal.valueOf(approved)));
        assertThat(reserved)
                .as("reserved must never exceed the credit limit")
                .isLessThanOrEqualTo(CREDIT_LIMIT);

        Integer approvedRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM authorizations WHERE card_id = ? AND status = 'APPROVED'",
                Integer.class, cardId);
        Integer declinedInsufficient = jdbc.queryForObject(
                "SELECT COUNT(*) FROM authorizations WHERE card_id = ? AND status = 'DECLINED' "
                        + "AND decline_reason = 'INSUFFICIENT_AVAILABLE_CREDIT'",
                Integer.class, cardId);
        assertThat(approvedRows).isEqualTo(EXPECTED_APPROVED);
        assertThat(declinedInsufficient).isEqualTo(THREADS - EXPECTED_APPROVED);
    }
}
