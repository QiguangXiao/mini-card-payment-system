package com.minicard.delayjob;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 恢复长期停留在 PROCESSING 的 delay jobs。
 *
 * <p>关键词：任务恢复, lease 超时, 死信, delay job recovery,
 * processing timeout, dead job, ジョブ復旧(ジョブふっきゅう),
 * タイムアウト。</p>
 *
 * <p>PROCESSING 是 lease，不是永久状态。worker/pod 宕机时，recoverer 会把超时任务
 * 重新放回 retry 状态或转 DEAD，保证 database-backed queue 不会卡死。</p>
 *
 * <p>recoverer 兜底的是"worker 在 claim 之后永远回不来"的时间线：</p>
 * <pre>
 * t0  worker claim:  PENDING -> PROCESSING(token=X, lease deadline=t0+processing-timeout-seconds)
 * t1  pod 宕机/进程被 kill：finalize 永远不会执行，row 停在 PROCESSING
 * t2  (>deadline) recoverer 扫描到超时 row -> 按一次失败处理:
 *       attempts+1 未达 maxAttempts -> PENDING(nextAttemptAt=now+backoff)
 *       attempts+1 >= maxAttempts -> DEAD
 * t3  poller 下一轮重新 claim（生成新 token），handler 重新执行
 * </pre>
 *
 * <p>注意 t1 有两种可能：handler 业务事务没执行，或已 commit 但 finalize 前宕机。
 * recoverer 无法区分，只能统一重跑——所以 handler 必须幂等（如 authorization expiry
 * 先检查状态仍是 APPROVED 才释放额度）。若 t1 的 worker 只是慢而非死，t3 之后它迟到
 * finalize 会因 leaseToken 不匹配被拒（见 DelayJobWorker 的 stale worker 时间线）。</p>
 */
@Component
// recoverer 也受同一个 enabled 开关控制；测试或本地排障时可以整体关掉后台任务扫描。
@ConditionalOnProperty(
        prefix = "delay-jobs.scheduler",
        name = "enabled",
        havingValue = "true"
)
@Slf4j
@RequiredArgsConstructor
public class DelayJobRecoverer {

    /** 查询并加锁超时 PROCESSING job。 */
    private final DelayJobRepository delayJobRepository;
    /** 控制每轮恢复数量和 maxAttempts。 */
    private final DelayJobProperties properties;
    /** 当前时间来源。 */
    private final Clock clock;

    /**
     * 周期性恢复 lease 超时任务。
     */
    // @Scheduled 只触发恢复扫描；真正的 row lock 和状态推进由下面的 @Transactional 包住。
    // 如果没有事务，findStuckProcessingBatchForUpdate 拿到的锁会立即释放，多实例可能重复恢复同一 job。
    @Scheduled(
            fixedDelayString = "${delay-jobs.scheduler.recovery-fixed-delay-ms:5000}",
            scheduler = "delayJobTaskScheduler"
    )
    // recover 扫描和状态更新在一个事务里完成，确保 FOR UPDATE SKIP LOCKED 的结果不会被别的实例同时改写。
    @Transactional
    public void recoverStuckJobs() {
        Instant now = Instant.now(clock);
        // 阶段 1：扫描 lease deadline 已过的 PROCESSING jobs，并用 SKIP LOCKED 避免多实例重复恢复。
        List<DelayJob> jobs = delayJobRepository.findStuckProcessingBatchForUpdate(
                now,
                properties.maxPerRun()
        );
        for (DelayJob job : jobs) {
            // 阶段 2：把超时 PROCESSING 当作一次失败，推进 retry/DEAD 状态机。
            // 超时 PROCESSING 按一次失败处理；超过 maxAttempts 后进入 DEAD，避免无限重试坏任务。
            // 如果没有这条恢复路径，worker 宕机后授权过期释放或自动还款 job 会永久卡住。
            job.markProcessingTimedOut(now, properties.maxAttempts());
            // 阶段 3：持久化恢复结果。回到 PENDING 的 job 会在下一轮 poll 中重新领取。
            delayJobRepository.updateExecutionState(job);
            log.warn(
                    "delay_job_recovered jobId={} jobType={} attempts={} status={}",
                    job.id(),
                    job.jobType(),
                    job.attempts(),
                    job.status()
            );
        }
    }
}
