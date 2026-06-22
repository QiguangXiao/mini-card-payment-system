package com.minicard.statement.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 每月账单批处理 use case。
 *
 * <p>关键词：账单批处理, 关账, 付款日, statement batch, billing cycle,
 * due date, 締め日(しめび), 請求確定(せいきゅうかくてい),
 * 支払日(しはらいび), 営業日(えいぎょうび)。</p>
 *
 * <p>真实 issuer backend 通常不是客户调用 HTTP 来生成账单，而是 billing batch 在固定日期
 * 找出到期账户，逐个复用 StatementService.generate(...)。这里让 batch 本身不持有大事务，
 * 每个 account 单独进入清晰的 transaction boundary。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StatementBatchService {

    /** 查询本轮应出账账户，具体 SQL 会按 posted transaction 范围筛选。 */
    private final StatementBatchRepository batchRepository;
    /** 复用单账户出账 use case，保留原有 row lock、idempotency 和 Outbox/DelayJob 写入顺序。 */
    private final StatementService statementService;
    /** 全局出账配置：締め日（close day）、支払基準日（payment base day）和 batch size。 */
    private final StatementBatchProperties properties;
    /** 日本营业日规则，用于把 27 日顺延到翌営業日。 */
    private final BusinessDayCalendar businessDayCalendar;
    /** 可注入 clock，测试可以固定 runDate，不依赖机器时间。 */
    private final Clock clock;

    /**
     * scheduler 调用入口：使用当前 UTC clock 计算今天是否该跑 statement batch。
     */
    public StatementBatchResult runDueBatch() {
        return runDueBatch(LocalDate.now(clock));
    }

    /**
     * 执行某一天的批处理。
     *
     * <p>runDate 是 batch 运行日；账单 periodEnd 是前一天，因为真实月结通常在締め日结束后
     * 的次日批处理，避免当天交易和出账 snapshot 发生时间边界混乱。</p>
     */
    StatementBatchResult runDueBatch(LocalDate runDate) {
        LocalDate periodEnd = runDate.minusDays(1);
        if (!isCloseDate(periodEnd)) {
            return StatementBatchResult.notDue(runDate);
        }

        BillingCycle cycle = billingCycle(periodEnd);
        Instant periodStartInclusive = cycle.periodStart().atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant periodEndExclusive = cycle.periodEnd().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        List<UUID> accountIds = batchRepository.findCreditAccountIdsWithUnbilledPostedTransactions(
                periodStartInclusive,
                periodEndExclusive,
                properties.maxAccountsPerRun()
        );

        int generated = 0;
        int skipped = 0;
        int failed = 0;
        for (UUID accountId : accountIds) {
            try {
                // 每个账户调用一次 StatementService，让账户 row lock、交易 FOR UPDATE、
                // statement unique key 和 Outbox/DelayJob 写入都留在小而明确的事务中。
                statementService.generate(new GenerateStatementCommand(
                        accountId,
                        cycle.periodStart(),
                        cycle.periodEnd(),
                        cycle.dueDate()
                ));
                generated++;
            } catch (StatementGenerationRejectedException exception) {
                skipped++;
                // 业务可预期拒绝，例如同一期已经出账；不应该让整批失败。
                log.info(
                        "statement_batch_skipped accountId={} periodStart={} periodEnd={} reason={}",
                        accountId,
                        cycle.periodStart(),
                        cycle.periodEnd(),
                        exception.getMessage()
                );
            } catch (RuntimeException exception) {
                failed++;
                // 单个账户失败不能中断整批；失败账户依赖下次 scheduler 重试或人工排查。
                // 真实系统通常还会落 batch_run_detail 表，这里先用 structured log。
                log.warn(
                        "statement_batch_failed accountId={} periodStart={} periodEnd={}",
                        accountId,
                        cycle.periodStart(),
                        cycle.periodEnd(),
                        exception
                );
            }
        }

        return new StatementBatchResult(
                true,
                runDate,
                cycle.periodStart(),
                cycle.periodEnd(),
                cycle.dueDate(),
                accountIds.size(),
                generated,
                skipped,
                failed
        );
    }

    /**
     * 计算账单周期和付款日。
     *
     * <p>当前采用全局 close day：上次締め日の次日到本次締め日。付款日不是固定下月 10 日，
     * 而是 periodEnd 之后的 27 日，若遇到非営業日则顺延。</p>
     */
    private BillingCycle billingCycle(LocalDate periodEnd) {
        // YearMonth 表达“月份”而不是某一天，适合处理 2 月/30 天/31 天这种账单月边界。
        // 如果直接用 minusDays(30)，短月和长月会把 billing cycle 算偏。
        YearMonth previousMonth = YearMonth.from(periodEnd).minusMonths(1);
        LocalDate previousCloseDate = dayInMonth(previousMonth, properties.closeDayOfMonth());
        return new BillingCycle(
                previousCloseDate.plusDays(1),
                periodEnd,
                paymentDateAfter(periodEnd)
        );
    }

    /**
     * 判断某天是否为締め日（statement close date）。
     */
    private boolean isCloseDate(LocalDate date) {
        return date.equals(dayInMonth(YearMonth.from(date), properties.closeDayOfMonth()));
    }

    /**
     * 计算 periodEnd 之后第一个可扣款的支払日（payment due date）。
     *
     * <p>日本工资常见 25 日入账，但银行入账存在时间差，所以本项目选择 27 日为基准；
     * 如果 27 日是周末或祝日，则通过 business calendar 顺延到翌営業日。</p>
     */
    private LocalDate paymentDateAfter(LocalDate periodEnd) {
        YearMonth candidateMonth = YearMonth.from(periodEnd);
        LocalDate candidate = businessDayCalendar.nextBusinessDayOnOrAfter(
                dayInMonth(candidateMonth, properties.paymentBaseDayOfMonth())
        );
        if (!candidate.isAfter(periodEnd)) {
            candidate = businessDayCalendar.nextBusinessDayOnOrAfter(
                    dayInMonth(candidateMonth.plusMonths(1), properties.paymentBaseDayOfMonth())
            );
        }
        return candidate;
    }

    /**
     * 处理 29/30/31 日这类月末配置：短月自动夹到当月最后一天。
     */
    private LocalDate dayInMonth(YearMonth month, int configuredDay) {
        return month.atDay(Math.min(configuredDay, month.lengthOfMonth()));
    }

    /**
     * 批处理内部值对象，避免在方法之间传散 periodStart/periodEnd/dueDate 三个强相关字段。
     */
    private record BillingCycle(
            LocalDate periodStart,
            LocalDate periodEnd,
            LocalDate dueDate
    ) {
        // private record 适合方法内部强相关值的轻量载体。
        // 如果三个 LocalDate 分散传参，调用点很容易把 periodEnd/dueDate 顺序传错。
    }
}
