package com.minicard.statement.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.minicard.statement.domain.StatementJob;
import com.minicard.statement.domain.StatementJobExecutionResult;
import com.minicard.statement.domain.StatementJobStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Statement jobs 的 claim、dispatch、recover、finalize 入口。
 *
 * <p>关键词：账单任务调度, DB claim, worker pool, PROCESSING lease,
 * statement job dispatcher, FOR UPDATE SKIP LOCKED, 請求ジョブディスパッチャー
 * (せいきゅうジョブディスパッチャー)。</p>
 *
 * <p>这是本项目 “claimable job” 的 reference dispatcher：一个类完成
 * poll → 短事务 claim → 交给 worker pool 执行 → finalize（DONE/重试/DEAD）→ recover 卡死 job。
 * 它刻意不像 delayjob 那样拆成 claimer/poller/worker/recoverer 四个类——合在一起更易读、易讲，
 * 其他 domain 的 job 之后可以对齐成这个形状。</p>
 *
 * <p>claim 是短事务：PENDING → PROCESSING 提交后才把 job 交给 worker，生成账单期间不再持有 job row lock。
 * finalize 时会重新校验 lease token，防止过期 worker 覆盖已被 recover/reclaim 的新状态。</p>
 */
@Component
@ConditionalOnProperty(
        prefix = "statement.jobs",
        name = "enabled",
        havingValue = "true"
)
@RequiredArgsConstructor
@Slf4j
public class StatementJobDispatcher {

    private final StatementJobRepository jobRepository;
    private final StatementJobHandler handler;
    private final StatementProperties properties;
    private final Clock clock;
    private final TransactionOperations transactionOperations;
    @Qualifier("statementJobWorkerExecutor")
    private final TaskExecutor statementJobWorkerExecutor;
    private final String workerId = "statement-worker-" + UUID.randomUUID();

    /**
     * 周期性领取可执行的账单分片任务，并提交给 worker pool。
     */
    @Scheduled(
            fixedDelayString = "${statement.jobs.fixed-delay-ms:1000}",
            scheduler = "statementJobTaskScheduler"
    )
    public void dispatch() {
        List<StatementJob> jobs = transactionOperations.execute(status -> claimJobs());
        if (jobs == null || jobs.isEmpty()) {
            return;
        }

        for (StatementJob job : jobs) {
            try {
                // claim commit 后才交给 worker pool；生成账单时不会继续持有 job row lock。
                statementJobWorkerExecutor.execute(() -> handleClaimedJob(job));
            } catch (TaskRejectedException exception) {
                markFailed(job, null, "statement job worker pool rejected job", exception);
                log.warn("statement_job_worker_rejected jobId={}", job.id(), exception);
            }
        }
    }

    /**
     * 周期性恢复 PROCESSING lease 超时的账单分片任务。
     */
    @Scheduled(
            fixedDelayString = "${statement.jobs.recovery-fixed-delay-ms:10000}",
            scheduler = "statementJobTaskScheduler"
    )
    public void recoverStuckJobs() {
        transactionOperations.executeWithoutResult(status -> {
            Instant now = Instant.now(clock);
            List<StatementJob> jobs = jobRepository.findStuckProcessingBatchForUpdate(
                    now,
                    properties.jobs().maxPerRun()
            );
            for (StatementJob job : jobs) {
                // PROCESSING 是 lease，不是永久拥有权。worker 宕机后必须放回 PENDING 或 DEAD。
                // 如果没有 recover，账单分片可能永久卡在 PROCESSING，本期出账永远无法完成。
                job.markFailed(
                        null,
                        "statement job processing lease expired",
                        now,
                        properties.jobs().maxAttempts()
                );
                jobRepository.updateExecutionState(job);
                log.warn(
                        "statement_job_recovered jobId={} attempts={} status={}",
                        job.id(),
                        job.attemptCount(),
                        job.status()
                );
            }
        });
    }

    /**
     * 在短事务里批量 claim PENDING statement jobs，并写入 PROCESSING lease。
     *
     * <p>事务归属：由 {@link #dispatch()} 通过 {@code TransactionOperations.execute(...)}
     * 调用，不依赖 {@code @Transactional} 注解；claim 完成 commit 后才提交 worker pool。</p>
     */
    private List<StatementJob> claimJobs() {
        Instant now = Instant.now(clock);
        List<StatementJob> jobs = jobRepository.findClaimableBatchForUpdate(
                now,
                properties.jobs().maxPerRun()
        );
        for (StatementJob job : jobs) {
            // PENDING -> PROCESSING 在短事务内提交；commit 后 worker 才真正生成账单。
            // 如果 claim 和业务处理放进一个大事务，上千账户的出账会放大 job row lock 时间。
            job.markProcessing(workerId, now, properties.jobs().processingTimeoutSeconds());
            jobRepository.updateExecutionState(job);
        }
        return jobs;
    }

    /**
     * 执行已领取的分片出账任务，并根据账户级结果 finalize 为 DONE 或失败重试。
     *
     * <p>事务归属：本方法运行在线程池中，外层没有 job row 事务；真正的账单生成事务在
     * {@link StatementJobHandler#handle(StatementJob)} 内部逐账户调用
     * {@link StatementGenerationService#generate(GenerateStatementCommand)} 打开。</p>
     */
    private void handleClaimedJob(StatementJob claimedJob) {
        try {
            StatementJobExecutionResult result = handler.handle(claimedJob);
            if (result.failedAccountCount() > 0) {
                markFailed(claimedJob, result, "one or more accounts failed in statement job", null);
            } else {
                markDone(claimedJob, result);
            }
        } catch (RuntimeException exception) {
            markFailed(
                    claimedJob,
                    null,
                    exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage(),
                    exception
            );
        }
    }

    /**
     * 在短事务中重新校验 lease，并把成功完成的分片任务标记为 DONE。
     *
     * <p>事务归属：本方法通过 {@code TransactionOperations.executeWithoutResult(...)}
     * 自己开启 finalize 短事务；不要把整个 handler 执行包进这个事务。</p>
     */
    private void markDone(StatementJob claimedJob, StatementJobExecutionResult result) {
        transactionOperations.executeWithoutResult(status -> {
            StatementJob job = lockCurrentLease(claimedJob);
            if (job == null) {
                return;
            }
            job.markDone(result, Instant.now(clock));
            jobRepository.updateExecutionState(job);
            log.info(
                    "statement_job_done jobId={} processed={} generated={} skipped={}",
                    job.id(),
                    job.processedAccountCount(),
                    job.generatedStatementCount(),
                    job.skippedAccountCount()
            );
        });
    }

    /**
     * 在短事务中重新校验 lease，并保存失败统计、错误原因和 retry/DEAD 状态。
     *
     * <p>事务归属：本方法通过 {@code TransactionOperations.executeWithoutResult(...)}
     * 自己开启 finalize 短事务；它和账单生成事务是分开的。</p>
     */
    private void markFailed(
            StatementJob claimedJob,
            StatementJobExecutionResult result,
            String error,
            RuntimeException exception
    ) {
        transactionOperations.executeWithoutResult(status -> {
            StatementJob job = lockCurrentLease(claimedJob);
            if (job == null) {
                return;
            }
            job.markFailed(result, error, Instant.now(clock), properties.jobs().maxAttempts());
            jobRepository.updateExecutionState(job);
            log.warn(
                    "statement_job_failed jobId={} attempts={} status={}",
                    job.id(),
                    job.attemptCount(),
                    job.status(),
                    exception
            );
        });
    }

    /**
     * 重新锁定当前 job row 并确认本 dispatcher worker 仍持有 claim token。
     *
     * <p>事务归属：只能在 {@link #markDone(StatementJob, StatementJobExecutionResult)}
     * 或 {@link #markFailed(StatementJob, StatementJobExecutionResult, String, RuntimeException)}
     * 创建的 finalize 短事务内部调用；否则 {@code FOR UPDATE} 锁不会覆盖后续状态更新。</p>
     */
    private StatementJob lockCurrentLease(StatementJob claimedJob) {
        StatementJob job = jobRepository.findByIdForUpdate(claimedJob.id())
                .orElseThrow(() -> new IllegalStateException(
                        "claimed statement job disappeared " + claimedJob.id()
                ));
        if (job.status() != StatementJobStatus.PROCESSING
                || !Objects.equals(job.claimToken(), claimedJob.claimToken())) {
            // 旧 worker 可能在 lease 过期后才回来；不能覆盖新 worker/recoverer 已经推进的状态。
            // 如果只比较 claim_until 这类 timestamp，DB/Java 精度差异或同 worker 重领都会让判断不稳；
            // claim_token 是每次 claim 的随机 owner token，迟到 worker 不能靠旧 token 覆盖新状态。
            log.warn(
                    "statement_job_lease_changed jobId={} claimedToken={} currentStatus={} currentToken={} currentLease={}",
                    claimedJob.id(),
                    claimedJob.claimToken(),
                    job.status(),
                    job.claimToken(),
                    job.claimUntil()
            );
            return null;
        }
        return job;
    }
}
