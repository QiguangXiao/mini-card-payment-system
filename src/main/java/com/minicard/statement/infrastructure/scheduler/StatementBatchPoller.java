package com.minicard.statement.infrastructure.scheduler;

import com.minicard.statement.application.StatementBatchResult;
import com.minicard.statement.application.StatementBatchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Statement batch 的轻量 scheduler。
 *
 * <p>@Scheduled 只负责周期性触发和记录结果；候选账户查询、每账户事务、
 * row lock 和 Outbox/DelayJob 写入都在 application service 内完成。</p>
 */
@Component
// ConditionalOnProperty 让本地/测试可以关闭 batch poller。
// 如果没有开关，测试启动 Spring context 时可能不断触发后台出账任务。
@ConditionalOnProperty(
        prefix = "statement.batch",
        name = "enabled",
        havingValue = "true"
)
@RequiredArgsConstructor
@Slf4j
public class StatementBatchPoller {

    private final StatementBatchService batchService;

    // @Scheduled 只负责触发，不负责持有大事务。
    // scheduler 指定专用线程池，避免 statement batch 和 outbox/delayjob 互相占用默认调度线程。
    @Scheduled(
            fixedDelayString = "${statement.batch.fixed-delay-ms:60000}",
            scheduler = "statementBatchTaskScheduler"
    )
    public void closeDueBillingCycles() {
        StatementBatchResult result = batchService.runDueBatch();
        if (!result.due()) {
            return;
        }
        log.info(
                "statement_batch_completed runDate={} periodStart={} periodEnd={} dueDate={} candidates={} generated={} skipped={} failed={}",
                result.runDate(),
                result.periodStart(),
                result.periodEnd(),
                result.dueDate(),
                result.candidateCount(),
                result.generatedCount(),
                result.skippedCount(),
                result.failedCount()
        );
    }
}
