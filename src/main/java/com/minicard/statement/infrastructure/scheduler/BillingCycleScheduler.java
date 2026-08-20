package com.minicard.statement.infrastructure.scheduler;

import com.minicard.statement.application.StatementBatchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 账单批处理的定时触发器。
 *
 * <p>关键词：定时触发, 账务时区, 出账批处理, scheduled trigger, billing timezone,
 * 締め処理(しめしょり), バッチ起動(バッチきどう)。</p>
 *
 * <p>刻意做得很薄：只负责「每天按 JST 醒一次」，账期推导和出账逻辑都在
 * {@link StatementBatchService} 里，scheduler 不需要知道 billing timezone 之外的任何业务规则。</p>
 *
 * <p>{@code zone} 必须显式指定：cron 表达式默认按 JVM 时区解释，容器时区通常是 UTC，
 * 那样「每天 01:00 出账」在日本实际会变成上午 10:00 跑，撞上在线交易高峰。</p>
 *
 * <p>生产落差：真实発卡行用 job scheduler（JP1/AJS、Control-M）或 Spring Batch /
 * Step Functions 在独立批处理系统上跑，不会让在线 API pod 兼职跑批。
 * 理由和取舍见 {@link StatementBatchService} 的类注释。</p>
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
    public void generateStatements() {
        int generated = batchService.run();
        if (generated > 0) {
            log.info("billing_cycle_statements_generated count={}", generated);
        }
    }
}
