package com.minicard.delayjob;

import java.util.List;

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
 * 业务处理和 DONE/FAILED finalize 都在 DelayJobWorker 内完成。</p>
 */
@Component
@ConditionalOnProperty(
        prefix = "delay-jobs.scheduler",
        name = "enabled",
        havingValue = "true"
)
public class DelayJobPoller {

    private static final Logger log = LoggerFactory.getLogger(DelayJobPoller.class);

    private final DelayJobClaimer claimer;
    private final DelayJobWorker worker;
    private final TaskExecutor delayJobWorkerExecutor;

    public DelayJobPoller(
            DelayJobClaimer claimer,
            DelayJobWorker worker,
            @Qualifier("delayJobWorkerExecutor") TaskExecutor delayJobWorkerExecutor
    ) {
        this.claimer = claimer;
        this.worker = worker;
        this.delayJobWorkerExecutor = delayJobWorkerExecutor;
    }

    @Scheduled(
            fixedDelayString = "${delay-jobs.scheduler.fixed-delay-ms:1000}",
            scheduler = "delayJobTaskScheduler"
    )
    public void pollDueJobs() {
        List<DelayJob> jobs = claimer.claimDueJobs();
        for (DelayJob job : jobs) {
            try {
                // commit 后才 submit 给 worker pool；worker 会重新校验 PROCESSING lease 后再 finalize。
                delayJobWorkerExecutor.execute(() -> worker.handleClaimedJob(job));
            } catch (TaskRejectedException exception) {
                // 如果应用正在 shutdown 或 worker queue 满了，不能让 job 永远卡在 PROCESSING。
                worker.markRejectedForRetry(job, exception);
                log.warn("delay_job_worker_rejected jobId={}", job.id(), exception);
            }
        }
    }
}
