package com.minicard.statement.infrastructure.scheduler;

import com.minicard.statement.application.StatementCycleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Billing cycle 的 daily scheduler。
 *
 * <p>关键词：账单周期调度, daily scheduler, job creation,
 * billing cycle scheduler, 締め日(しめび), 日次起動(にちじきどう)。</p>
 *
 * <p>它每天醒来一次，只负责判断是否需要创建本期 statement jobs。
 * 真正生成账单由 StatementJobDispatcher 的 worker 通过 DB claim 并行处理。</p>
 */
@Component
@ConditionalOnProperty(
        prefix = "statement.batch",
        name = "enabled",
        havingValue = "true"
)
@RequiredArgsConstructor
@Slf4j
public class BillingCycleScheduler {

    private final StatementCycleService cycleService;

    @Scheduled(
            cron = "${statement.batch.cron:0 0 1 * * *}",
            zone = "${statement.batch.zone:Asia/Tokyo}",
            scheduler = "billingCycleTaskScheduler"
    )
    public void createDueStatementJobs() {
        // 阶段 1：daily scheduler 在配置的账务时区醒来。
        // 这里不直接生成所有账单，只触发一次"对账式补建"检查，避免 cron 漏跑后永远丢失某天的 job。
        // 阶段 2：StatementCycleService 查询当前 due 的 billing cycles，并用 DB 唯一约束保证幂等创建。
        int shardCount = cycleService.createDueJobs();
        // 阶段 3：只记录有实际创建的批次。没有 due cycle 是正常空跑，不需要刷日志。
        if (shardCount > 0) {
            log.info("billing_cycle_jobs_created shardCount={}", shardCount);
        }
    }
}
