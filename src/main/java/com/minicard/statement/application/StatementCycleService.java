package com.minicard.statement.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import com.minicard.statement.domain.StatementJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 把一个到期的 billing cycle 规划成 sharded statement jobs。
 *
 * <p>关键词：账单周期规划, 分片任务创建, 出账日, statement cycle planning,
 * sharded job creation, close date, 締め処理(しめしょり),
 * 請求ジョブ作成(せいきゅうジョブさくせい)。</p>
 *
 * <p>它不生成任何单账户 statement，只负责把“本期要出账”落成 durable claimable jobs，
 * 之后由 StatementJobDispatcher 通过 DB claim 并行执行。没有 parent batch 行：
 * “本期是否全部出账完成”用 statement_jobs 上按 cycle 的查询回答即可，
 * 避免再维护一张 batch 生命周期表。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StatementCycleService {

    private final StatementJobRepository jobRepository;
    private final StatementBillingRepository billingRepository;
    private final StatementProperties properties;
    private final BusinessDayCalendar businessDayCalendar;
    private final Clock clock;

    /**
     * 用当前日期（按 billing timezone）对最近几个已过去关账周期做 reconciliation。
     *
     * <p>事务归属：scheduler 实际调用这个入口，所以这里是 job creation 的事务入口。
     * 这里不再只看“昨天是不是关账日”，而是做 level-triggered catch-up scan：
     * 最近几个已过去 close cycle 缺哪个 {@code statement_jobs} 就补哪个，避免应用错过某次
     * cron tick 后该周期永远不出账。</p>
     */
    @Transactional
    public int createDueJobs() {
        // 阶段 1：按账务时区取今天，而不是 JVM 默认时区。
        // 账单关账/还款日是业务日期；时区不明确会导致跨日边界错建或漏建。
        LocalDate today = LocalDate.now(clock.withZone(batchZone()));
        // 阶段 2：做 desired-state reconciliation。缺失周期补建，已存在周期跳过。
        return reconcileDueCycles(today);
    }

    /**
     * 为某个 runDate 对应的 billing cycle 创建 sharded jobs，作为测试和 runbook 精确补跑入口。
     *
     * <p>只有“runDate 的昨天”是 close date 时才创建。
     * 创建是幂等的（INSERT IGNORE + cycle/shard 唯一键），重复触发不会产生重复分片。
     * 如果不幂等，scheduler 多实例、手工补跑或当天重跑会把同一周期的分片创建多份。</p>
     *
     * <p>事务归属：外部测试或手工入口可以直接调用本方法，因此保留 {@code @Transactional}。
     * scheduler 的自动补偿入口是 {@link #createDueJobs()}，不会依赖本方法的 self-invocation。</p>
     *
     * @return 本期分片数；非 close date 返回 0。
     */
    @Transactional
    public int createDueJobs(LocalDate runDate) {
        // 阶段 1：手工/测试入口按 runDate 的前一天判断是否是 close date。
        LocalDate periodEnd = runDate.minusDays(1);
        if (!isCloseDate(periodEnd)) {
            return 0;
        }

        // 阶段 2：先用 cycle 唯一键判断是否已规划过，减少重复计算。
        // 幂等最终仍靠 INSERT IGNORE + 唯一键兜底，多实例同时进来也不会重复创建。
        LocalDate periodStart = periodStartFor(periodEnd);
        if (jobRepository.existsForCycle(periodStart, periodEnd)) {
            return 0;
        }
        // 阶段 3：确定账期和 due date 后，落成 sharded statement_jobs。
        BillingCycle cycle = billingCycle(periodEnd);
        return createJobsForCycle(cycle);
    }

    /**
     * 对最近几个已过去的关账周期做 desired-state reconciliation，缺失的周期会被补建分片。
     *
     * <p>事务归属：只由 {@link #createDueJobs()} 调用，加入 scheduler 心跳的同一个事务。
     * 多实例同时扫描也安全：{@link StatementJobRepository#existsForCycle(LocalDate, LocalDate)}
     * 只是减少无谓计算，最终幂等性仍由 {@code INSERT IGNORE} 和 cycle/shard 唯一键兜底。</p>
     */
    private int reconcileDueCycles(LocalDate today) {
        int created = 0;
        LocalDate latestClosedDate = today.minusDays(1);
        // 阶段 1：回看最近 N 个已过去 close dates。
        // 这不是"只看昨天"的 edge-triggered cron，而是能补应用停机/cron 漏跑的 level-triggered scan。
        for (LocalDate closeDate : recentCloseDatesOnOrBefore(
                latestClosedDate,
                properties.batch().reconciliationLookbackCycles()
        )) {
            // 阶段 2：每个 close date 独立判断是否已有分片。
            LocalDate periodStart = periodStartFor(closeDate);
            if (jobRepository.existsForCycle(periodStart, closeDate)) {
                log.debug(
                        "statement_cycle_jobs_already_exist periodStart={} periodEnd={}",
                        periodStart,
                        closeDate
                );
                continue;
            }
            // 阶段 3：缺失周期立即补建；同一事务内由唯一键保证重复触发安全。
            BillingCycle cycle = billingCycle(closeDate);
            created += createJobsForCycle(cycle);
        }
        return created;
    }

    /**
     * 为一个确定的 billing cycle 创建分片 jobs。
     *
     * <p>事务归属：由 {@link #createDueJobs(LocalDate)} 或 {@link #reconcileDueCycles(LocalDate)}
     * 在各自已打开的 job creation 事务中调用。</p>
     */
    private int createJobsForCycle(BillingCycle cycle) {
        // 阶段 1：把业务账期日期转成查询交易流水的时间窗口。
        // periodEndExclusive 用次日零点，避免在 SQL 里处理 23:59:59.999999 这种精度边界。
        Instant periodStartInclusive = cycle.periodStart().atStartOfDay(batchZone()).toInstant();
        Instant periodEndExclusive = cycle.periodEnd().plusDays(1).atStartOfDay(batchZone()).toInstant();
        long accountCount = billingRepository.countBillableAccounts(periodStartInclusive, periodEndExclusive);
        // 阶段 2：根据待出账账户量决定分片数；没有账户也创建 1 个空分片保留生命周期。
        int shardCount = shardCount(accountCount);
        Instant now = Instant.now(clock);

        // 阶段 3：生成本期所有 PENDING statement_jobs。
        // 所有分片在一个事务内 INSERT IGNORE：要么整批写入，要么因唯一键幂等跳过，不会留下半套分片。
        List<StatementJob> jobs = IntStream.range(0, shardCount)
                .mapToObj(shardNo -> StatementJob.pending(
                        cycle.periodStart(),
                        cycle.periodEnd(),
                        cycle.dueDate(),
                        shardNo,
                        shardCount,
                        now
                ))
                .toList();
        jobRepository.insertAll(jobs);
        log.info(
                "statement_cycle_jobs_created periodStart={} periodEnd={} dueDate={} accountCount={} shardCount={}",
                cycle.periodStart(),
                cycle.periodEnd(),
                cycle.dueDate(),
                accountCount,
                shardCount
        );
        return shardCount;
    }

    /**
     * 从 latestDate 所在月份往前找最近几个已经到达的 close dates。
     *
     * <p>事务归属：纯日期计算方法；当前由 {@link #reconcileDueCycles(LocalDate)}
     * 在 scheduler reconciliation 事务中调用。</p>
     */
    private List<LocalDate> recentCloseDatesOnOrBefore(LocalDate latestDate, int maxCycles) {
        List<LocalDate> closeDates = new ArrayList<>();
        YearMonth month = YearMonth.from(latestDate);
        while (closeDates.size() < maxCycles) {
            LocalDate closeDate = dayInMonth(month, properties.batch().closeDayOfMonth());
            if (!closeDate.isAfter(latestDate)) {
                closeDates.add(closeDate);
            }
            month = month.minusMonths(1);
        }
        return closeDates;
    }

    /**
     * 根据本期待出账账户数决定分片数量，控制每个 job 的账户规模。
     *
     * <p>事务归属：纯计算方法；当前由 {@link #createJobsForCycle(BillingCycle)}
     * 在 job creation 事务中调用，但不依赖事务能力。</p>
     */
    private int shardCount(long accountCount) {
        if (accountCount == 0) {
            // 仍创建 1 个空分片，让本期有完整生命周期；worker 处理 0 个账户后直接标 DONE。
            return 1;
        }
        return (int) Math.ceil((double) accountCount / properties.batch().targetAccountsPerJob());
    }

    /**
     * 根据关账日推导账期开始日、结束日和还款到期日。
     *
     * <p>事务归属：纯日期计算方法；当前由 {@link #createDueJobs(LocalDate)}
     * 或 {@link #reconcileDueCycles(LocalDate)} 在 job creation 事务中调用。</p>
     */
    private BillingCycle billingCycle(LocalDate periodEnd) {
        return new BillingCycle(
                periodStartFor(periodEnd),
                periodEnd,
                paymentDateAfter(periodEnd)
        );
    }

    /**
     * 根据关账日推导账期开始日，供 existsForCycle 在计算 due date 前先判断是否已经规划过。
     *
     * <p>事务归属：纯日期计算方法；当前由 {@link #createDueJobs(LocalDate)}、
     * {@link #reconcileDueCycles(LocalDate)} 和 {@link #billingCycle(LocalDate)}
     * 在 job creation 事务中调用。</p>
     */
    private LocalDate periodStartFor(LocalDate periodEnd) {
        YearMonth previousMonth = YearMonth.from(periodEnd).minusMonths(1);
        return dayInMonth(previousMonth, properties.batch().closeDayOfMonth()).plusDays(1);
    }

    /**
     * 判断某一天是否是配置中的关账日。
     *
     * <p>事务归属：纯日期判断；当前由 {@link #createDueJobs(LocalDate)} 在事务开始后调用，
     * 但它本身不访问数据库。</p>
     */
    private boolean isCloseDate(LocalDate date) {
        return date.equals(dayInMonth(YearMonth.from(date), properties.batch().closeDayOfMonth()));
    }

    /**
     * 计算严格晚于关账日的还款到期日，并按营业日规则顺延。
     *
     * <p>事务归属：纯日期计算方法；当前由 {@link #billingCycle(LocalDate)}
     * 在 job creation 事务中间接调用。</p>
     */
    private LocalDate paymentDateAfter(LocalDate periodEnd) {
        YearMonth candidateMonth = YearMonth.from(periodEnd);
        LocalDate candidate = businessDayCalendar.nextBusinessDayOnOrAfter(
                dayInMonth(candidateMonth, properties.batch().paymentBaseDayOfMonth())
        );
        // due date 必须严格晚于 period end；如果按本月支付基准日算出来不晚于关账日，就顺延到下个月。
        if (!candidate.isAfter(periodEnd)) {
            candidate = businessDayCalendar.nextBusinessDayOnOrAfter(
                    dayInMonth(candidateMonth.plusMonths(1), properties.batch().paymentBaseDayOfMonth())
            );
        }
        return candidate;
    }

    /**
     * 取某个月的配置日；短月份会落到当月最后一天。
     *
     * <p>事务归属：纯日期计算方法；当前由 {@link #billingCycle(LocalDate)}
     * 和 {@link #isCloseDate(LocalDate)} 间接服务 job creation 事务。</p>
     */
    private LocalDate dayInMonth(YearMonth month, int configuredDay) {
        return month.atDay(Math.min(configuredDay, month.lengthOfMonth()));
    }

    /**
     * 返回账单批处理使用的业务时区。
     *
     * <p>事务归属：纯配置读取方法，不依赖事务；当前服务 {@link #createDueJobs()}、
     * {@link #createDueJobs(LocalDate)} 和 {@link #createJobsForCycle(BillingCycle)}。</p>
     */
    private ZoneId batchZone() {
        return ZoneId.of(properties.batch().zone());
    }

    private record BillingCycle(
            LocalDate periodStart,
            LocalDate periodEnd,
            LocalDate dueDate
    ) {
    }
}
