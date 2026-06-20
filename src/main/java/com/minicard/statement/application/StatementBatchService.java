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
 * <p>真实 issuer backend 通常不是客户调用 HTTP 来生成账单，而是 billing batch 在固定日期
 * 找出到期账户，逐个复用 StatementService.generate(...)。这里让 batch 本身不持有大事务，
 * 每个 account 单独进入清晰的 transaction boundary。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StatementBatchService {

    private final StatementBatchRepository batchRepository;
    private final StatementService statementService;
    private final StatementBatchProperties properties;
    private final Clock clock;

    public StatementBatchResult runDueBatch() {
        return runDueBatch(LocalDate.now(clock));
    }

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

    private BillingCycle billingCycle(LocalDate periodEnd) {
        YearMonth previousMonth = YearMonth.from(periodEnd).minusMonths(1);
        LocalDate previousCloseDate = dayInMonth(previousMonth, properties.closeDayOfMonth());
        return new BillingCycle(
                previousCloseDate.plusDays(1),
                periodEnd,
                paymentDateAfter(periodEnd)
        );
    }

    private boolean isCloseDate(LocalDate date) {
        return date.equals(dayInMonth(YearMonth.from(date), properties.closeDayOfMonth()));
    }

    private LocalDate paymentDateAfter(LocalDate periodEnd) {
        YearMonth candidateMonth = YearMonth.from(periodEnd);
        LocalDate candidate = dayInMonth(candidateMonth, properties.paymentDayOfMonth());
        if (!candidate.isAfter(periodEnd)) {
            candidate = dayInMonth(candidateMonth.plusMonths(1), properties.paymentDayOfMonth());
        }
        return candidate;
    }

    private LocalDate dayInMonth(YearMonth month, int configuredDay) {
        return month.atDay(Math.min(configuredDay, month.lengthOfMonth()));
    }

    private record BillingCycle(
            LocalDate periodStart,
            LocalDate periodEnd,
            LocalDate dueDate
    ) {
    }
}
