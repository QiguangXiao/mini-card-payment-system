package com.minicard.statement.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
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
     * 用当前日期（按 billing timezone）判断是否需要创建本期 jobs。
     */
    public int createDueJobs() {
        LocalDate runDate = LocalDate.now(clock.withZone(batchZone()));
        return createDueJobs(runDate);
    }

    /**
     * 为某个 runDate 对应的 billing cycle 创建 sharded jobs。
     *
     * <p>daily scheduler 每天触发；只有“昨天”是 close date 时才创建。
     * 创建是幂等的（INSERT IGNORE + cycle/shard 唯一键），重复触发不会产生重复分片。
     * 如果不幂等，scheduler 多实例或当天重跑会把同一周期的分片创建多份。</p>
     *
     * @return 本期分片数；非 close date 返回 0。
     */
    @Transactional
    public int createDueJobs(LocalDate runDate) {
        LocalDate periodEnd = runDate.minusDays(1);
        if (!isCloseDate(periodEnd)) {
            return 0;
        }

        BillingCycle cycle = billingCycle(periodEnd);
        Instant periodStartInclusive = cycle.periodStart().atStartOfDay(batchZone()).toInstant();
        Instant periodEndExclusive = cycle.periodEnd().plusDays(1).atStartOfDay(batchZone()).toInstant();
        long accountCount = billingRepository.countBillableAccounts(periodStartInclusive, periodEndExclusive);
        int shardCount = shardCount(accountCount);
        Instant now = Instant.now(clock);

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

    private int shardCount(long accountCount) {
        if (accountCount == 0) {
            // 仍创建 1 个空分片，让本期有完整生命周期；worker 处理 0 个账户后直接标 DONE。
            return 1;
        }
        return (int) Math.ceil((double) accountCount / properties.batch().targetAccountsPerJob());
    }

    private BillingCycle billingCycle(LocalDate periodEnd) {
        YearMonth previousMonth = YearMonth.from(periodEnd).minusMonths(1);
        LocalDate previousCloseDate = dayInMonth(previousMonth, properties.batch().closeDayOfMonth());
        return new BillingCycle(
                previousCloseDate.plusDays(1),
                periodEnd,
                paymentDateAfter(periodEnd)
        );
    }

    private boolean isCloseDate(LocalDate date) {
        return date.equals(dayInMonth(YearMonth.from(date), properties.batch().closeDayOfMonth()));
    }

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

    private LocalDate dayInMonth(YearMonth month, int configuredDay) {
        return month.atDay(Math.min(configuredDay, month.lengthOfMonth()));
    }

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
