package com.minicard.statement.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import com.minicard.statement.domain.StatementBatch;
import com.minicard.statement.domain.StatementBatchStatus;
import com.minicard.statement.domain.StatementJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 创建 monthly statement batch 和 sharded jobs 的 use case。
 *
 * <p>关键词：账单批次创建, daily scheduler, 分片任务, statement batch creation,
 * sharded statement jobs, 締め処理(しめしょり), 請求ジョブ作成(せいきゅうジョブさくせい)。</p>
 *
 * <p>这个 service 不生成任何单账户 statement。它只把“本期要出账”落成 durable
 * batch/jobs，后续由 worker 通过 DB claim 并行执行。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StatementBatchService {

    private final StatementBatchRepository batchRepository;
    private final StatementJobRepository jobRepository;
    private final StatementBillingRepository billingRepository;
    private final StatementProperties properties;
    private final BusinessDayCalendar businessDayCalendar;
    private final Clock clock;

    public StatementBatchCreationResult createDueBatch() {
        LocalDate runDate = LocalDate.now(clock.withZone(batchZone()));
        return createDueBatch(runDate);
    }

    /**
     * 创建某个 runDate 对应的 billing batch。
     *
     * <p>daily scheduler 每天触发；只有昨天是 close date 时才创建 batch。
     * 这样比 60 秒轮询更贴近 calendar-driven billing，也方便未来补跑 missed cycles。</p>
     */
    @Transactional
    public StatementBatchCreationResult createDueBatch(LocalDate runDate) {
        LocalDate periodEnd = runDate.minusDays(1);
        if (!isCloseDate(periodEnd)) {
            return StatementBatchCreationResult.notDue(runDate);
        }

        BillingCycle cycle = billingCycle(periodEnd);
        Instant periodStartInclusive = cycle.periodStart().atStartOfDay(batchZone()).toInstant();
        Instant periodEndExclusive = cycle.periodEnd().plusDays(1).atStartOfDay(batchZone()).toInstant();
        long accountCount = billingRepository.countBillableAccounts(periodStartInclusive, periodEndExclusive);
        int jobCount = jobCount(accountCount);
        Instant now = Instant.now(clock);
        StatementBatch batch = StatementBatch.start(
                cycle.periodStart(),
                cycle.periodEnd(),
                cycle.dueDate(),
                accountCount,
                properties.batch().targetAccountsPerJob(),
                jobCount,
                now
        );

        boolean inserted = batchRepository.insert(batch);
        if (!inserted) {
            log.info(
                    "statement_batch_already_exists periodStart={} periodEnd={}",
                    cycle.periodStart(),
                    cycle.periodEnd()
            );
            return new StatementBatchCreationResult(
                    true,
                    false,
                    runDate,
                    cycle.periodStart(),
                    cycle.periodEnd(),
                    cycle.dueDate(),
                    null,
                    accountCount,
                    jobCount
            );
        }

        List<StatementJob> jobs = IntStream.range(0, jobCount)
                .mapToObj(shardNo -> StatementJob.pending(batch.id(), shardNo, jobCount, now))
                .toList();
        jobRepository.insertAll(jobs);
        log.info(
                "statement_batch_created batchId={} periodStart={} periodEnd={} accountCount={} jobCount={}",
                batch.id(),
                cycle.periodStart(),
                cycle.periodEnd(),
                accountCount,
                jobCount
        );
        return new StatementBatchCreationResult(
                true,
                true,
                runDate,
                cycle.periodStart(),
                cycle.periodEnd(),
                cycle.dueDate(),
                batch.id(),
                accountCount,
                jobCount
        );
    }

    /**
     * 根据所有 jobs 的状态推进 batch 完成。
     *
     * <p>Dispatcher 在每个 job finalize 后调用这里；方法内部重新锁 batch，
     * 让“最后一个完成的 job”成为唯一能把 batch 从 RUNNING 推到终态的 worker。
     * 如果不在这里做 row lock，两个 worker 同时看到 all-finished 时会重复更新 batch 终态。</p>
     */
    @Transactional
    public void completeBatchIfAllJobsFinished(UUID batchId) {
        StatementBatch batch = batchRepository.findByIdForUpdate(batchId);
        if (batch.status() != StatementBatchStatus.RUNNING) {
            return;
        }

        StatementJobStatusSummary summary = jobRepository.summarizeByBatchId(batchId);
        if (!summary.allFinished()) {
            return;
        }

        Instant now = Instant.now(clock);
        if (summary.hasDeadJobs()) {
            batch.markPartiallyFailed("one or more statement jobs are DEAD", now);
        } else {
            batch.markCompleted(now);
        }
        batchRepository.updateState(batch);
    }

    private int jobCount(long accountCount) {
        if (accountCount == 0) {
            // 仍创建 1 个空 job，让 batch 有完整生命周期；worker 会处理 0 个账户并标 DONE。
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
