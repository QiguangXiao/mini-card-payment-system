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
 * 业务处理和 DONE/retry/DEAD finalize 都在 DelayJobWorker 内完成。</p>
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
    /** 内部 DB 小事务类 job 的线程池（如授权过期）；避免 @Scheduled 线程直接跑长业务。 */
    private final TaskExecutor delayJobWorkerExecutor;
    /** AUTO_REPAYMENT 专用线程池：银行外部调用只钉住本池，不拖住授权过期。 */
    private final TaskExecutor autoRepaymentDelayJobWorkerExecutor;

    public DelayJobPoller(
            DelayJobClaimer claimer,
            DelayJobWorker worker,
            @Qualifier("delayJobWorkerExecutor") TaskExecutor delayJobWorkerExecutor,
            @Qualifier("autoRepaymentDelayJobWorkerExecutor") TaskExecutor autoRepaymentDelayJobWorkerExecutor
    ) {
        // @Qualifier 指定业务 worker pool。省掉后，多个 TaskExecutor bean 会导致启动歧义或注入错误线程池。
        this.claimer = claimer;
        this.worker = worker;
        this.delayJobWorkerExecutor = delayJobWorkerExecutor;
        this.autoRepaymentDelayJobWorkerExecutor = autoRepaymentDelayJobWorkerExecutor;
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
                // 阶段 3：worker 执行业务 handler，并在结束后自行 finalize 为 DONE、retry(PENDING) 或 DEAD。
                // commit 后才 submit 给 worker pool；worker 会重新校验 PROCESSING lease 后再 finalize。
                // 池按 jobType 路由（见 executorFor）：claim 仍是一批混领，隔离发生在执行层。
                executorFor(job).execute(() -> worker.handleClaimedJob(job));
            } catch (TaskRejectedException exception) {
                // 阶段 4：提交 worker pool 失败也要持久化失败状态。
                // 如果应用正在 shutdown 或 worker queue 满了，不能让 job 永远卡在 PROCESSING。
                worker.markRejectedForRetry(job, exception);
                log.warn("delay_job_worker_rejected jobId={}", job.id(), exception);
            }
        }
    }

    /**
     * 按 job 类型选择执行线程池：外部调用类 job 与纯 DB 类 job 分池隔离。
     *
     * <p>AUTO_REPAYMENT 要同步等银行网关（最长到 Feign read-timeout），银行 brownout 时
     * 只会钉住专用池；授权过期释放额度这类延迟敏感的内部 job 不受连带影响。
     * 未来新增"会调外部系统"的 jobType 时，路由到专用池（或再建一个）即可。</p>
     */
    private TaskExecutor executorFor(DelayJob job) {
        return job.jobType() == DelayJobType.AUTO_REPAYMENT
                ? autoRepaymentDelayJobWorkerExecutor
                : delayJobWorkerExecutor;
    }
}
