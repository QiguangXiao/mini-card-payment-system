package com.minicard.scheduling.application;

import java.util.List;

import com.minicard.scheduling.domain.DelayJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 通用延迟任务 poller。
 *
 * <p>@Scheduled 只负责周期性 poll、短事务 claim、提交 worker pool；
 * 业务处理和 DONE/FAILED finalize 都在 ScheduledJobWorker 内完成。</p>
 */
@Component
@ConditionalOnProperty(
        prefix = "delay-jobs.scheduler",
        name = "enabled",
        havingValue = "true"
)
public class ScheduledJobPoller {

    private static final Logger log = LoggerFactory.getLogger(ScheduledJobPoller.class);

    private final ScheduledJobClaimService claimService;
    private final ScheduledJobWorker worker;
    private final TaskExecutor scheduledJobWorkerExecutor;

    public ScheduledJobPoller(
            ScheduledJobClaimService claimService,
            ScheduledJobWorker worker,
            @Qualifier("scheduledJobWorkerExecutor") TaskExecutor scheduledJobWorkerExecutor
    ) {
        this.claimService = claimService;
        this.worker = worker;
        this.scheduledJobWorkerExecutor = scheduledJobWorkerExecutor;
    }

    @Scheduled(
            fixedDelayString = "${delay-jobs.scheduler.fixed-delay-ms:1000}",
            scheduler = "delayJobTaskScheduler"
    )
    public void pollDueJobs() {
        List<DelayJob> jobs = claimService.claimDueJobs();
        for (DelayJob job : jobs) {
            try {
                // commit 后才 submit 给 worker pool；worker 会重新校验 PROCESSING lease 后再 finalize。
                scheduledJobWorkerExecutor.execute(() -> worker.handleClaimedJob(job));
            } catch (TaskRejectedException exception) {
                // 如果应用正在 shutdown 或 worker queue 满了，不能让 job 永远卡在 PROCESSING。
                worker.markRejectedForRetry(job, exception);
                log.warn("delay_job_worker_rejected jobId={}", job.id(), exception);
            }
        }
    }
}
