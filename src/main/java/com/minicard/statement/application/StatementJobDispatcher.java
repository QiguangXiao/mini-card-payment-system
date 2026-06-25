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
 * Statement jobs 的 claim、dispatch、recover 和 finalize 入口。
 *
 * <p>关键词：账单任务调度, DB claim, worker pool, PROCESSING lease,
 * statement job dispatcher, FOR UPDATE SKIP LOCKED, 請求ジョブディスパッチャー
 * (せいきゅうジョブディスパッチャー)。</p>
 *
 * <p>为了适合 mini-card 学习项目，dispatcher 合并了原先 claimer/recoverer/worker/poller
 * 的技术动作；业务生成仍交给 StatementJobHandler 和 StatementGenerationService。
 * claim 是短事务，worker finalize 时会重新校验 lease，防止过期 worker 覆盖新状态。</p>
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
    private final StatementBatchService batchService;
    private final StatementProperties properties;
    private final Clock clock;
    private final TransactionOperations transactionOperations;
    @Qualifier("statementJobWorkerExecutor")
    private final TaskExecutor statementJobWorkerExecutor;
    private final String workerId = "statement-worker-" + UUID.randomUUID();

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
                // 如果没有 recover，账单分片可能永久卡住，batch 也永远无法完成。
                job.markFailed(
                        null,
                        "statement job processing lease expired",
                        now,
                        properties.jobs().maxAttempts()
                );
                jobRepository.updateExecutionState(job);
                log.warn(
                        "statement_job_recovered jobId={} batchId={} attempts={} status={}",
                        job.id(),
                        job.batchId(),
                        job.attemptCount(),
                        job.status()
                );
                batchService.completeBatchIfAllJobsFinished(job.batchId());
            }
        });
    }

    private List<StatementJob> claimJobs() {
        Instant now = Instant.now(clock);
        List<StatementJob> jobs = jobRepository.findClaimableBatchForUpdate(
                now,
                properties.jobs().maxPerRun()
        );
        for (StatementJob job : jobs) {
            // PENDING -> PROCESSING 在短事务内提交；commit 后 worker 才真正生成账单。
            // 如果 claim 和业务处理放进一个大事务，1000 个账户的出账会放大 job row lock 时间。
            job.markProcessing(workerId, now, properties.jobs().processingTimeoutSeconds());
            jobRepository.updateExecutionState(job);
        }
        return jobs;
    }

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

    private void markDone(StatementJob claimedJob, StatementJobExecutionResult result) {
        transactionOperations.executeWithoutResult(status -> {
            StatementJob job = lockCurrentLease(claimedJob);
            if (job == null) {
                return;
            }
            job.markDone(result, Instant.now(clock));
            jobRepository.updateExecutionState(job);
            log.info(
                    "statement_job_done jobId={} batchId={} processed={} generated={} skipped={}",
                    job.id(),
                    job.batchId(),
                    job.processedAccountCount(),
                    job.generatedStatementCount(),
                    job.skippedAccountCount()
            );
            batchService.completeBatchIfAllJobsFinished(job.batchId());
        });
    }

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
                    "statement_job_failed jobId={} batchId={} attempts={} status={}",
                    job.id(),
                    job.batchId(),
                    job.attemptCount(),
                    job.status(),
                    exception
            );
            batchService.completeBatchIfAllJobsFinished(job.batchId());
        });
    }

    private StatementJob lockCurrentLease(StatementJob claimedJob) {
        StatementJob job = jobRepository.findByIdForUpdate(claimedJob.id())
                .orElseThrow(() -> new IllegalStateException(
                        "claimed statement job disappeared " + claimedJob.id()
                ));
        if (job.status() != StatementJobStatus.PROCESSING
                || !Objects.equals(job.claimUntil(), claimedJob.claimUntil())) {
            // 旧 worker 可能在 lease 过期后才回来；不能覆盖新 worker/recoverer 已经推进的状态。
            // 如果不校验 lease token，迟到 worker 会把别人处理中的 job 错误标 DONE。
            log.warn(
                    "statement_job_lease_changed jobId={} claimedLease={} currentStatus={} currentLease={}",
                    claimedJob.id(),
                    claimedJob.claimUntil(),
                    job.status(),
                    job.claimUntil()
            );
            return null;
        }
        return job;
    }
}
