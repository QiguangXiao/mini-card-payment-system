package com.minicard.statement.infrastructure.scheduler;

import com.minicard.statement.application.StatementBatchCreationResult;
import com.minicard.statement.application.StatementBatchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Billing cycle 的 daily scheduler。
 *
 * <p>关键词：账单周期调度, daily scheduler, batch creation,
 * billing cycle scheduler, 締め日(しめび), 日次起動(にちじきどう)。</p>
 *
 * <p>它每天醒来一次，只负责判断是否需要创建 statement_batch 和 statement_jobs。
 * 真正生成账单由 job worker 通过 DB claim 并行处理。</p>
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

    private final StatementBatchService batchService;

    @Scheduled(
            cron = "${statement.batch.cron:0 0 1 * * *}",
            zone = "${statement.batch.zone:Asia/Tokyo}",
            scheduler = "billingCycleTaskScheduler"
    )
    public void createDueStatementBatch() {
        StatementBatchCreationResult result = batchService.createDueBatch();
        if (!result.due()) {
            return;
        }
        log.info(
                "billing_cycle_checked runDate={} periodStart={} periodEnd={} created={} batchId={} accountCount={} jobCount={}",
                result.runDate(),
                result.periodStart(),
                result.periodEnd(),
                result.created(),
                result.batchId(),
                result.accountCount(),
                result.jobCount()
        );
    }
}
