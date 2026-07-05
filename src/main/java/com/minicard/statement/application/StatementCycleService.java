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
 * <p>这里的 {@code cycle} 不是一张表，而是一个账期值对象：
 * {@code periodStart + periodEnd + dueDate}。例如 {@code 2026-07-01 ~ 2026-07-31}
 * 是消费归属窗口，{@code dueDate=2026-08-27} 是这张账单的付款期限。
 * 这个值先写入 {@code statement_jobs}，后续逐账户成功出账时再写入 {@code statements}。</p>
 *
 * <p>本 service 不生成任何单账户 statement，也不插入 {@code statement_lines}。
 * 它只负责把“某个账期需要出账”规划成 durable claimable jobs；
 * 之后由 {@link StatementJobDispatcher} claim 分片，再由 {@link StatementGenerationService}
 * 为每个账户创建真正的 {@code statements + statement_lines}。没有 parent batch 行：
 * “本期是否全部出账完成”用 {@code statement_jobs} 上按 cycle 的查询回答即可，
 * 避免再维护一张 batch 生命周期表。</p>
 *
 * <p>流程总览（mini trace，level-triggered，不是"只看昨天"的 edge-triggered cron）：</p>
 * <pre>
 * scheduler 每日 tick 调 createDueJobs()
 * 1. today = LocalDate.now(billing timezone, Asia/Tokyo)
 * 2. 回看最近 N 个已过去的 close dates（补 cron 漏跑/应用停机的周期），对每个 close date:
 *    2.1 该 cycle 在 statement_jobs 里已有任意 shard: skip（说明这个账期已被 planner 规划过；
 *        不代表所有账户都已经生成 statement）
 *    2.2 缺失时才推导账期 [periodStart, periodEnd] 和营业日顺延后的 dueDate
 *    2.3 count billable accounts 决定 shardCount：
 *        只统计本账期有 POSTED + UNBILLED 交易的账户，不是全量账户数
 *    2.4 INSERT IGNORE 整批 PENDING statement_jobs（cycle/shard 唯一键兜底幂等）
 * 3. COMMIT（之后 StatementJobDispatcher claim 分片并行执行）
 * </pre>
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
     * cron tick 后该周期永远不出账。这里的“缺”指 {@code statement_jobs} 中不存在这个
     * {@code periodStart + periodEnd} 的任何 shard，不是指每个账户都缺 {@code statements}。</p>
     */
    @Transactional
    public int createDueJobs() {
        // 阶段 1：按账务时区取今天，而不是 JVM 默认时区。
        // 账单关账/还款日是业务日期；时区不明确会导致跨日边界错建或漏建。
        LocalDate today = LocalDate.now(clock.withZone(batchZone()));
        // 阶段 2：做 desired-state reconciliation。缺失周期补建，已存在周期跳过。
        // 例子：服务 8/1 停机错过 7 月账单创建，8/3 恢复后仍会回看 7/31 close cycle 并补建。
        return reconcileDueCycles(today);
    }

    /**
     * 为某个 runDate 对应的 billing cycle 创建 sharded jobs，作为测试和 runbook 精确补跑入口。
     *
     * <p>只有“runDate 的昨天”是 close date 时才创建。
     * runDate 是“调度器醒来的业务日”，periodEnd 是“刚完整结束的关账日”。
     * 例如 runDate=2026-08-01，periodEnd=2026-07-31。</p>
     *
     * <p>
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

        // 阶段 2：先用 cycle 身份(periodStart + periodEnd)判断是否已规划过，减少重复计算。
        // existsForCycle 只要看到任意 shard 就返回 true，语义是“这个 cycle 已经被 planner 接管过”。
        // 这不是在判断所有账户是否都已经生成 statement；账户级完成度要看 statement_jobs 的 DONE/DEAD
        // 或 statements 的自然键，而不是由 scheduler 再扫一遍全量账户。
        //
        // 为什么“有一个 shard 就算有”在当前实现成立：
        // 1) createJobsForCycle 在同一个事务里生成本 cycle 的整套 shard；
        // 2) insertAll 内部逐条 INSERT IGNORE，但事务未提交前外部看不到半套结果；
        // 3) uk_statement_jobs_cycle_shard(period_start, period_end, shard_no) 保证重复 planner 不会制造第二套 shard。
        //
        // 什么时候需要升级成全局检查：
        // - 如果未来允许运维手工删除/重建某个 shard；
        // - 如果改成分批提交 shard，而不是一个事务里插入整套；
        // - 如果需要在 scheduler 层主动修复“只有 shard 0，没有 shard 1/2”的 DB 损坏。
        // 那时可以改成检查 COUNT(*) 是否等于 MAX(shard_count)，或加 cycle-level read model。
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
     *
     * <p>这里不要查 {@code statements} 判断缺失。某个 cycle 是否“已规划”，看的是
     * {@code statement_jobs}；某个账户是否“已出账”，才看 {@code statements} 的自然键。
     * 两层分开后，scheduler 只负责创建任务，账户级幂等由 {@link StatementGenerationService} 保护。</p>
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
            // “已有”指这个 cycle 已经出现过至少一个 statement_jobs row，代表 planner 已经创建过本期任务；
            // 它不代表所有账户都已出账完成，也不代表本期没有 DEAD shard。
            // 这里刻意不做全局完成度检查，因为 scheduler 的职责只是“缺任务就补建”：
            // - 是否执行完成：由 StatementJobDispatcher 推进 DONE/DEAD，并可按 cycle 查询 statement_jobs 状态；
            // - 是否单账户已出账：由 StatementGenerationService 的 statements 自然键保护；
            // - 是否 shard 集合完整：当前由 createJobsForCycle 单事务插入整套 shard + 唯一键保证。
            // 未来若要防手工改库/半套 shard，可把 existsForCycle 升级成完整性检查，而不是在这里扫所有账户。
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
            // 只有缺失时才计算 dueDate 和 shardCount，避免已存在周期重复做营业日/账户统计计算。
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
        // 只统计“这个账期内有待出账交易的账户数”：
        // status=POSTED + billing_status=UNBILLED + posted_at 落在本 cycle。
        // 不是全量 credit_accounts 数；10 万账户里只有 2500 个本期有消费，就按 2500 估算分片。
        long accountCount = billingRepository.countBillableAccounts(periodStartInclusive, periodEndExclusive);
        // 阶段 2：根据待出账账户量决定分片数；没有账户也创建 1 个空分片保留生命周期。
        int shardCount = shardCount(accountCount);
        Instant now = Instant.now(clock);

        // 阶段 3：生成本期所有 PENDING statement_jobs。
        // 一个 statement_jobs row 不是一张账单，而是一个分片任务；真正的 statement 在 worker 逐账户生成。
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
     * <p>例子：close-day-of-month=31，today=2026-08-03，则 latestDate=2026-08-02。
     * 最近已过去 close date 包括 2026-07-31、2026-06-30。这样服务如果错过 8/1 的 cron，
     * 8/3 恢复后仍能补建 7 月账单 job。</p>
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
     * <p>accountCount 是 {@code COUNT(DISTINCT credit_account_id)}，来源于本账期
     * {@code POSTED + UNBILLED} 的交易候选，不是系统全量账户数。这样空账户不会参与分片。</p>
     *
     * <p>事务归属：纯计算方法；当前由 {@link #createJobsForCycle(BillingCycle)}
     * 在 job creation 事务中调用，但不依赖事务能力。</p>
     */
    private int shardCount(long accountCount) {
        if (accountCount == 0) {
            // 仍创建 1 个空分片，让本期有完整生命周期；worker 处理 0 个账户后直接标 DONE。
            // 如果不创建空分片，后续只看 statement_jobs 会分不清“本期确实无人出账”和“scheduler 漏跑”。
            return 1;
        }
        return (int) Math.ceil((double) accountCount / properties.batch().targetAccountsPerJob());
    }

    /**
     * 根据关账日推导账期开始日、结束日和还款到期日。
     *
     * <p>cycle 是“这次要出账的业务窗口”，不是数据库 row：
     * periodStart/periodEnd 决定哪些 posted transactions 能进入账单，
     * dueDate 决定 AUTO_REPAYMENT DelayJob 和用户还款期限。</p>
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
     * <p>先算 periodStart，是因为 cycle 是否缺失只需要 {@code periodStart + periodEnd}；
     * dueDate 只有在真正缺失、需要创建 jobs 时才计算。</p>
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
