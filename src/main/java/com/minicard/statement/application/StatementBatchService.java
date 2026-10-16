package com.minicard.statement.application;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 月次账单批处理（締め処理）的极简实现：把刚结束的账期逐账户出账。
 *
 * <p>关键词：账单批处理, 账期, 逐账户事务, 故障隔离, statement batch, billing cycle,
 * per-account transaction, fault isolation, 締め処理(しめしょり), 請求バッチ(せいきゅうバッチ)。</p>
 *
 * <h2>为什么是这个形态（interview要能主动说出来）</h2>
 *
 * <p>生产上的発卡行不会用应用内 {@code @Scheduled} 跑出账：真实形态是 job scheduler
 * （JP1/AJS、A-AUTO、Control-M）编排 ジョブネット，或 Spring Batch / AWS Step Functions，
 * 跑在与在线 API 隔离的批处理系统上，有 リラン/リスタート 入口和与勘定系的突合。
 * 应用内 cron 的四个硬伤：与在线请求抢同一 JVM 和连接池、发版即重启批处理、
 * 没有 job 级可观测性和重跑入口、无法表达「上游数据没到齐就不许跑」的依赖。</p>
 *
 * <p>本项目刻意只保留最小触发器，把学习重点放在
 * {@link StatementGenerationService} 的幂等、锁顺序和 UNBILLED→BILLED 状态机上。
 * 分片 fan-out、durable job 表、claim/lease/recover 曾经在这里实现过一遍，
 * 后来判断对本项目是过度投入而移除；设计记录保留在 {@code docs/claimable-jobs-cn.md}，
 * 同一套 claim-lease 模式在 {@code messaging/outbox} 和 {@code delayjob} 里仍有完整实现。</p>
 *
 * <h2>流程（mini trace）</h2>
 *
 * <pre>
 * BillingCycleScheduler 每日 JST tick 调 run()
 * 1. 由「今天」推出刚结束的自然月账期：periodStart=上月1日, periodEnd=上月末日
 * 2. dueDate = 次月 payment-base-day，落在非营业日则顺延（ConfiguredBusinessDayCalendar）
 * 3. 查本账期还有 POSTED + UNBILLED 交易的账户
 * 4. 逐账户调 StatementGenerationService.generate(...)，每账户一个独立事务
 * 5. 汇总 generated/skipped/failed 打一行日志
 * </pre>
 *
 * <p>为什么不需要「本期是否已跑过」的状态表：待出账账户查询本身就是 level-triggered 的
 * desired state——账户一旦出账，它的交易被标成 BILLED，第二天就不会再出现在候选集合里。
 * 反向事实：如果用「昨天是不是关账日」这种 edge-triggered 判断，应用在关账次日宕机错过一次 cron，
 * 该账期就永远不会出账；而现在当月内任何一天恢复，下一次 tick 都会把漏掉的账户补出账。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StatementBatchService {

    private final StatementBillingRepository billingRepository;
    private final StatementGenerationService generationService;
    private final BusinessDayCalendar businessDayCalendar;
    private final StatementProperties properties;
    private final Clock clock;

    /**
     * 为刚结束的账期出账，返回本次真正生成的账单数。
     *
     * <p>注意这里<b>没有</b>{@code @Transactional}：整批不能是一个大事务。
     * 反向事实：把上千个账户放进一个事务，一个脏账户回滚会连坐整批，
     * 而且事务期间一直持有所有 credit account 行锁，在线还款会被长时间阻塞。
     * 事务边界在 {@link StatementGenerationService#generate} 内部，一账户一个。</p>
     */
    public int run() {
        BillingCycle cycle = closedCycle();
        List<UUID> accountIds = billingRepository.findBillableAccountIds(
                cycle.periodStart().atStartOfDay(batchZone()).toInstant(),
                cycle.periodEnd().plusDays(1).atStartOfDay(batchZone()).toInstant()
        );

        int generated = 0;
        int skipped = 0;
        int failed = 0;
        for (UUID accountId : accountIds) {
            try {
                generationService.generate(new GenerateStatementCommand(
                        accountId,
                        cycle.periodStart(),
                        cycle.periodEnd(),
                        cycle.dueDate()
                ));
                generated++;
            } catch (StatementGenerationException exception) {
                // 业务上「这个账户本期不该出账」（例如候选交易已被并发出账抢走）不是错误，计入 skipped。
                skipped++;
                log.info("statement_account_skipped accountId={} reason={}", accountId, exception.getMessage());
            } catch (RuntimeException exception) {
                // 单账户失败不中断整批：吞掉异常继续下一个账户，本账户的交易仍是 UNBILLED，
                // 明天的 tick 会自然重试。如果这里直接抛出，一个坏账户会挡住后面所有账户出账。
                failed++;
                log.warn("statement_account_failed accountId={}", accountId, exception);
            }
        }
        log.info(
                "statement_batch_done periodStart={} periodEnd={} dueDate={} candidates={} generated={} skipped={} failed={}",
                cycle.periodStart(), cycle.periodEnd(), cycle.dueDate(),
                accountIds.size(), generated, skipped, failed
        );
        return generated;
    }

    /**
     * 推导刚结束的账期。本项目简化为自然月账期（月末关账），不支持客户自定义 billing day。
     *
     * <p>用「上一个自然月」而不是「昨天是不是关账日」，这样当月任何一天跑都能补上漏掉的出账。</p>
     */
    private BillingCycle closedCycle() {
        YearMonth closedMonth = YearMonth.from(LocalDate.now(clock.withZone(batchZone()))).minusMonths(1);
        LocalDate periodEnd = closedMonth.atEndOfMonth();
        return new BillingCycle(closedMonth.atDay(1), periodEnd, dueDateAfter(closedMonth));
    }

    /**
     * 扣款日 = 账期次月的 payment-base-day，落在周末/祝日则顺延到下一个营业日。
     *
     * <p>25 日常见为工资日，默认 27 日扣款给工资入账留缓冲。次月基准日一定晚于账期结束日，
     * 所以不需要额外判断「算出来的 due date 是不是已经早于关账日」。</p>
     */
    private LocalDate dueDateAfter(YearMonth closedMonth) {
        YearMonth paymentMonth = closedMonth.plusMonths(1);
        int day = Math.min(properties.batch().paymentBaseDayOfMonth(), paymentMonth.lengthOfMonth());
        return businessDayCalendar.nextBusinessDayOnOrAfter(paymentMonth.atDay(day));
    }

    private ZoneId batchZone() {
        return ZoneId.of(properties.batch().zone());
    }

    /** 账期值对象：消费归属窗口 + 还款到期日，只在本次批处理内存活，不落表。 */
    private record BillingCycle(LocalDate periodStart, LocalDate periodEnd, LocalDate dueDate) {
    }
}
