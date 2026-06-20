package com.minicard.delayjob;

import java.util.List;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 通用延迟任务 poller。
 *
 * <p>关键词：定时扫描, 短事务领取, worker pool, delay job poller,
 * scheduled polling, task rejection, 遅延ジョブポーラー(ちえんジョブポーラー),
 * 定期実行(ていきじっこう)。</p>
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
@Slf4j
public class DelayJobPoller {

    /** 短事务 claim 组件，负责 PENDING -> PROCESSING lease。 */
    private final DelayJobClaimer claimer;
    /** 真正执行业务 handler 和 finalize 的 worker。 */
    private final DelayJobWorker worker;
    /** 后台业务线程池；避免 @Scheduled 线程直接跑长业务。 */
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

    /**
     * 周期性领取到期任务并提交给 worker pool。
     */
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
