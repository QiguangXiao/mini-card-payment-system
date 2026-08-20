package com.minicard.statement.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.minicard.statement.domain.StatementJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

/**
 * Billing cycle reconciliation 与 statement job 分片规划测试。
 *
 * <p>关键词：账单周期, 漏跑补偿, 分片规划, billing cycle,
 * reconciliation, sharded jobs, 請求締め処理(せいきゅうしめしょり)。</p>
 *
 * <p>这些测试保护 scheduler 的 level-triggered 语义：应用错过某次 cron 后仍能补建缺失 cycle，
 * 并按候选账户数计算 shardCount。它不执行单账户出账，避免把 planner 和 worker 职责混在一起。</p>
 */
class StatementCycleServiceTest {

    /** JST 2026-07-01 09:00：6/30（月末）是刚结束的关账日。 */
    private static final Clock JST_2026_07_01 =
            Clock.fixed(Instant.parse("2026-07-01T00:00:00Z"), ZoneOffset.UTC);

    private StatementJobRepository jobRepository;
    private StatementBillingRepository billingRepository;
    private BusinessDayCalendar businessDayCalendar;

    @BeforeEach
    void setUp() {
        jobRepository = mock(StatementJobRepository.class);
        billingRepository = mock(StatementBillingRepository.class);
        businessDayCalendar = mock(BusinessDayCalendar.class);
    }

    /**
     * 按"当前业务时间 + 回看窗口"装配 planner。
     *
     * <p>scheduler 只有一个入口 {@code createDueJobs()}，"今天是哪天"完全由注入的 {@link Clock} 决定。
     * 反向事实：如果为了测试保留一个 {@code createDueJobs(runDate)} 重载，生产代码就多一条
     * 只有测试会走的分支（而且它是 edge-triggered 语义），真实 scheduler 的补跑行为反而测不到。</p>
     */
    private StatementCycleService service(Clock clock, int lookbackCycles) {
        return new StatementCycleService(
                jobRepository,
                billingRepository,
                statementProperties(lookbackCycles),
                businessDayCalendar,
                clock
        );
    }

    @Test
    @SuppressWarnings("unchecked")
    // 测试目的：验证关账日次日会创建本周期的 sharded statement jobs。
    // variant：2500 个账户、每片 1000 个账户，期望生成 3 个 shard，due date 严格晚于 periodEnd。
    void createsShardedJobsForTheClosedCycleOnTheDayAfterCloseDate() {
        // close-day=31 表示月末关账：runDate=7/1 的“昨天”6/30 是 6 月关账日。
        when(billingRepository.countBillableAccounts(any(), any())).thenReturn(2500L);
        // 6/27 是周末 → 顺延到的工作日仍不晚于关账日 6/30，因此 due date 顺延到下个月基准日 7/27。
        when(businessDayCalendar.nextBusinessDayOnOrAfter(LocalDate.parse("2026-06-27")))
                .thenReturn(LocalDate.parse("2026-06-29"));
        when(businessDayCalendar.nextBusinessDayOnOrAfter(LocalDate.parse("2026-07-27")))
                .thenReturn(LocalDate.parse("2026-07-27"));

        int shardCount = service(JST_2026_07_01, 1).createDueJobs();

        // 2500 账户 / 每片 1000 = 3 个分片。
        assertThat(shardCount).isEqualTo(3);
        ArgumentCaptor<List<StatementJob>> captor = ArgumentCaptor.forClass(List.class);
        verify(jobRepository).insertAll(captor.capture());
        List<StatementJob> jobs = captor.getValue();
        assertThat(jobs).hasSize(3);
        assertThat(jobs).allSatisfy(job -> {
            assertThat(job.periodStart()).isEqualTo(LocalDate.parse("2026-06-01"));
            assertThat(job.periodEnd()).isEqualTo(LocalDate.parse("2026-06-30"));
            assertThat(job.dueDate()).isEqualTo(LocalDate.parse("2026-07-27"));
            assertThat(job.shardCount()).isEqualTo(3);
        });
        assertThat(jobs.stream().map(StatementJob::shardNo).toList()).containsExactly(0, 1, 2);
    }

    @Test
    @SuppressWarnings("unchecked")
    // 测试目的：验证 planner 只规划"已经关账"的周期，当期账单不会被提前出账。
    // variant：今天是 7/15，7 月关账日 7/31 还没到，回看只应命中 6 月 cycle。
    void doesNotPlanTheCurrentCycleBeforeItsCloseDateHasPassed() {
        Clock midJuly = Clock.fixed(Instant.parse("2026-07-15T00:00:00Z"), ZoneOffset.UTC);
        when(billingRepository.countBillableAccounts(any(), any())).thenReturn(10L);
        when(businessDayCalendar.nextBusinessDayOnOrAfter(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        int shardCount = service(midJuly, 1).createDueJobs();

        assertThat(shardCount).isEqualTo(1);
        // 7 月 cycle（7/1~7/31）尚未关账，连"是否已规划"都不该去查。
        verify(jobRepository, never()).existsForCycle(
                eq(LocalDate.parse("2026-07-01")),
                eq(LocalDate.parse("2026-07-31"))
        );
        ArgumentCaptor<List<StatementJob>> captor = ArgumentCaptor.forClass(List.class);
        verify(jobRepository).insertAll(captor.capture());
        assertThat(captor.getValue()).singleElement().satisfies(job -> {
            assertThat(job.periodStart()).isEqualTo(LocalDate.parse("2026-06-01"));
            assertThat(job.periodEnd()).isEqualTo(LocalDate.parse("2026-06-30"));
        });
    }

    @Test
    // 测试目的：验证已有 cycle job 时快速跳过，避免重复创建分片。
    // variant：existsForCycle=true，不再 count billable accounts，也不 insertAll。
    void skipsCloseCycleWhenStatementJobsAlreadyExist() {
        when(jobRepository.existsForCycle(LocalDate.parse("2026-06-01"), LocalDate.parse("2026-06-30")))
                .thenReturn(true);

        int shardCount = service(JST_2026_07_01, 1).createDueJobs();

        assertThat(shardCount).isZero();
        verify(billingRepository, never()).countBillableAccounts(any(), any());
        verify(jobRepository, never()).insertAll(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    // 测试目的：验证 scheduler 入口是 reconciliation，而不是只处理昨天一个 edge trigger。
    // variant：lookback=2，5 月 cycle 已存在、6 月缺失，只补建 6 月 jobs。
    void schedulerEntryReconcilesRecentClosedCyclesAndCreatesOnlyMissingOnes() {
        // CLOCK 的 JST 日期是 2026-07-01；lookback=2 会检查 6/30 和 5/31 两个已过去关账日。
        when(jobRepository.existsForCycle(LocalDate.parse("2026-05-01"), LocalDate.parse("2026-05-31")))
                .thenReturn(true);
        when(jobRepository.existsForCycle(LocalDate.parse("2026-06-01"), LocalDate.parse("2026-06-30")))
                .thenReturn(false);
        when(billingRepository.countBillableAccounts(any(), any())).thenReturn(1200L);
        when(businessDayCalendar.nextBusinessDayOnOrAfter(LocalDate.parse("2026-06-27")))
                .thenReturn(LocalDate.parse("2026-06-29"));
        when(businessDayCalendar.nextBusinessDayOnOrAfter(LocalDate.parse("2026-07-27")))
                .thenReturn(LocalDate.parse("2026-07-27"));

        int shardCount = service(JST_2026_07_01, 2).createDueJobs();

        assertThat(shardCount).isEqualTo(2);
        verify(jobRepository).existsForCycle(
                eq(LocalDate.parse("2026-06-01")),
                eq(LocalDate.parse("2026-06-30"))
        );
        verify(jobRepository).existsForCycle(
                eq(LocalDate.parse("2026-05-01")),
                eq(LocalDate.parse("2026-05-31"))
        );
        ArgumentCaptor<List<StatementJob>> captor = ArgumentCaptor.forClass(List.class);
        verify(jobRepository).insertAll(captor.capture());
        assertThat(captor.getValue()).hasSize(2);
        assertThat(captor.getValue()).allSatisfy(job -> {
            assertThat(job.periodStart()).isEqualTo(LocalDate.parse("2026-06-01"));
            assertThat(job.periodEnd()).isEqualTo(LocalDate.parse("2026-06-30"));
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    // 测试目的：验证 due date 使用 BusinessDayCalendar 顺延。
    // variant：下月 payment base day 落在非营业日，最终 due date 顺延到下一个营业日。
    void dueDateFollowsBusinessDayCalendarWhenBaseDayIsWeekend() {
        // 0 账户仍创建 1 个空分片，便于单独断言 due date。
        when(billingRepository.countBillableAccounts(any(), any())).thenReturn(0L);
        when(businessDayCalendar.nextBusinessDayOnOrAfter(LocalDate.parse("2026-06-27")))
                .thenReturn(LocalDate.parse("2026-06-29"));
        // 模拟下个月基准日 7/27 落在周末 → 顺延到 7/28 工作日。
        when(businessDayCalendar.nextBusinessDayOnOrAfter(LocalDate.parse("2026-07-27")))
                .thenReturn(LocalDate.parse("2026-07-28"));

        int shardCount = service(JST_2026_07_01, 1).createDueJobs();

        assertThat(shardCount).isEqualTo(1);
        ArgumentCaptor<List<StatementJob>> captor = ArgumentCaptor.forClass(List.class);
        verify(jobRepository).insertAll(captor.capture());
        assertThat(captor.getValue()).singleElement()
                .satisfies(job -> assertThat(job.dueDate()).isEqualTo(LocalDate.parse("2026-07-28")));
    }

    private StatementProperties statementProperties(int reconciliationLookbackCycles) {
        return new StatementProperties(
                // bank-holidays 传空集合：这些测试用 mock 的 BusinessDayCalendar 控制顺延，
                // 真实祝日数据的行为由 ConfiguredBusinessDayCalendarTest 覆盖。
                new StatementProperties.Batch(
                        true, "0 0 1 * * *", "Asia/Tokyo", 31, 27,
                        reconciliationLookbackCycles, 1000, Set.of()
                ),
                new StatementProperties.Jobs(true, 1000, 10000, 10, 3, 300, 4, 20),
                new StatementProperties.Policy(
                        new BigDecimal("0.10"),
                        Map.of("JPY", new BigDecimal("1000.00"))
                )
        );
    }
}
