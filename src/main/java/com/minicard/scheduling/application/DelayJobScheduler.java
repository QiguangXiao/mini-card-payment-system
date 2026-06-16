package com.minicard.scheduling.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 通用延迟任务 scheduler，负责周期性触发 DelayJobService。
 */
@Component
@ConditionalOnProperty(
        prefix = "delay-jobs.scheduler",
        name = "enabled",
        havingValue = "true"
)
public class DelayJobScheduler {

    private final DelayJobService delayJobService;
    private final DelayJobSchedulerProperties properties;

    public DelayJobScheduler(
            DelayJobService delayJobService,
            DelayJobSchedulerProperties properties
    ) {
        this.delayJobService = delayJobService;
        this.properties = properties;
    }

    @Scheduled(
            fixedDelayString = "${delay-jobs.scheduler.fixed-delay-ms:1000}",
            scheduler = "delayJobTaskScheduler"
    )
    public void dispatchDueJobs() {
        // @Scheduled 使用独立 delayJobTaskScheduler worker pool。
        // 它和 Outbox polling 形状相同，但业务 job 可能持有 account lock，所以不和消息发布共用线程池。
        for (int processed = 0; processed < properties.maxPerRun(); processed++) {
            if (!delayJobService.dispatchNext()) {
                return;
            }
        }
    }
}
