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
// @ConditionalOnProperty 让本地/测试可以关闭后台扫描。
// 如果没有这个开关，集成测试或临时启动应用时可能意外消费真实 due jobs。
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
        // @Qualifier 指定业务 worker pool。省掉后，多个 TaskExecutor bean 会导致启动歧义或注入错误线程池。
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
        // 阶段 1：短事务 claim 到期 job。这里只拿 lease，不执行业务动作。
        // claim 提交后，其他 pod 会看到这些 job 已是 PROCESSING，不会重复领取。
        // scheduler 指向专用 ThreadPoolTaskScheduler。
        // 如果不指定，多个 @Scheduled 任务会共享默认调度线程，慢任务更容易互相拖住。
        List<DelayJob> jobs = claimer.claimDueJobs();
        // 阶段 2：把已领取 job 提交给业务 worker pool。
        // @Scheduled 线程只负责触发和提交，不能直接跑授权过期/自动还款这种可能持锁的业务。
        for (DelayJob job : jobs) {
            try {
                // 阶段 3：worker 执行业务 handler，并在结束后自行 finalize DONE/FAILED。
                // commit 后才 submit 给 worker pool；worker 会重新校验 PROCESSING lease 后再 finalize。
                delayJobWorkerExecutor.execute(() -> worker.handleClaimedJob(job));
            } catch (TaskRejectedException exception) {
                // 阶段 4：提交 worker pool 失败也要持久化失败状态。
                // 如果应用正在 shutdown 或 worker queue 满了，不能让 job 永远卡在 PROCESSING。
                worker.markRejectedForRetry(job, exception);
                log.warn("delay_job_worker_rejected jobId={}", job.id(), exception);
            }
        }
    }
}
