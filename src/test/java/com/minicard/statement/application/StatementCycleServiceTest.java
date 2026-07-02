package com.minicard.statement.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

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

class StatementCycleServiceTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-07-01T00:00:00Z"), ZoneOffset.UTC);

    private StatementJobRepository jobRepository;
    private StatementBillingRepository billingRepository;
    private BusinessDayCalendar businessDayCalendar;
    private StatementCycleService service;

    @BeforeEach
    void setUp() {
        jobRepository = mock(StatementJobRepository.class);
        billingRepository = mock(StatementBillingRepository.class);
        businessDayCalendar = mock(BusinessDayCalendar.class);
        service = new StatementCycleService(
                jobRepository,
                billingRepository,
                statementProperties(),
                businessDayCalendar,
                CLOCK
        );
    }

    @Test
    @SuppressWarnings("unchecked")
    void createsShardedJobsForTheClosedCycleOnTheDayAfterCloseDate() {
        // close-day=31 表示月末关账：runDate=7/1 的“昨天”6/30 是 6 月关账日。
        when(billingRepository.countBillableAccounts(any(), any())).thenReturn(2500L);
        // 6/27 是周末 → 顺延到的工作日仍不晚于关账日 6/30，因此 due date 顺延到下个月基准日 7/27。
        when(businessDayCalendar.nextBusinessDayOnOrAfter(LocalDate.parse("2026-06-27")))
                .thenReturn(LocalDate.parse("2026-06-29"));
        when(businessDayCalendar.nextBusinessDayOnOrAfter(LocalDate.parse("2026-07-27")))
                .thenReturn(LocalDate.parse("2026-07-27"));

        int shardCount = service.createDueJobs(LocalDate.parse("2026-07-01"));

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
    void skipsWhenRunDateDoesNotFollowACloseDate() {
        // runDate=7/2 的“昨天”7/1 不是关账日（关账日是月末），不应创建任何 job。
        int shardCount = service.createDueJobs(LocalDate.parse("2026-07-02"));

        assertThat(shardCount).isZero();
        verify(billingRepository, never()).countBillableAccounts(any(), any());
        verify(jobRepository, never()).insertAll(any());
    }

    @Test
    void skipsCloseCycleWhenStatementJobsAlreadyExist() {
        when(jobRepository.existsForCycle(LocalDate.parse("2026-06-01"), LocalDate.parse("2026-06-30")))
                .thenReturn(true);

        int shardCount = service.createDueJobs(LocalDate.parse("2026-07-01"));

        assertThat(shardCount).isZero();
        verify(billingRepository, never()).countBillableAccounts(any(), any());
        verify(jobRepository, never()).insertAll(any());
    }

    @Test
    @SuppressWarnings("unchecked")
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

        int shardCount = service.createDueJobs();

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
    void dueDateFollowsBusinessDayCalendarWhenBaseDayIsWeekend() {
        // 0 账户仍创建 1 个空分片，便于单独断言 due date。
        when(billingRepository.countBillableAccounts(any(), any())).thenReturn(0L);
        when(businessDayCalendar.nextBusinessDayOnOrAfter(LocalDate.parse("2026-06-27")))
                .thenReturn(LocalDate.parse("2026-06-29"));
        // 模拟下个月基准日 7/27 落在周末 → 顺延到 7/28 工作日。
        when(businessDayCalendar.nextBusinessDayOnOrAfter(LocalDate.parse("2026-07-27")))
                .thenReturn(LocalDate.parse("2026-07-28"));

        int shardCount = service.createDueJobs(LocalDate.parse("2026-07-01"));

        assertThat(shardCount).isEqualTo(1);
        ArgumentCaptor<List<StatementJob>> captor = ArgumentCaptor.forClass(List.class);
        verify(jobRepository).insertAll(captor.capture());
        assertThat(captor.getValue()).singleElement()
                .satisfies(job -> assertThat(job.dueDate()).isEqualTo(LocalDate.parse("2026-07-28")));
    }

    private StatementProperties statementProperties() {
        return new StatementProperties(
                new StatementProperties.Batch(true, "0 0 1 * * *", "Asia/Tokyo", 31, 27, 2, 1000),
                new StatementProperties.Jobs(true, 1000, 10000, 10, 3, 300, 4, 20),
                new StatementProperties.Policy(
                        new BigDecimal("0.10"),
                        Map.of("JPY", new BigDecimal("1000.00"))
                )
        );
    }
}
