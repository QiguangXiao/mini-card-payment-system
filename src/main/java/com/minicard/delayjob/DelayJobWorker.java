package com.minicard.delayjob;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Delay job worker，负责执行业务 handler，并由 worker 自己 finalize。
 *
 * <p>面试重点：claim 已经在短事务内完成；worker 只处理已经拿到 PROCESSING lease 的 job。
 * 成功后标 DONE，失败后按 retry policy 回到 PENDING 或进入 DEAD。</p>
 */
@Service
public class DelayJobWorker {

    private static final Logger log = LoggerFactory.getLogger(DelayJobWorker.class);

    private final DelayJobRepository delayJobRepository;
    private final DelayJobProperties properties;
    private final Map<DelayJobType, DelayJobHandler> handlers;
    private final Clock clock;
    private final TransactionOperations transactionOperations;

    public DelayJobWorker(
            DelayJobRepository delayJobRepository,
            DelayJobProperties properties,
            List<DelayJobHandler> handlers,
            Clock clock,
            TransactionOperations transactionOperations
    ) {
        this.delayJobRepository = delayJobRepository;
        this.properties = properties;
        this.handlers = handlersByType(handlers);
        this.clock = clock;
        this.transactionOperations = transactionOperations;
    }

    public void handleClaimedJob(DelayJob claimedJob) {
        DelayJobHandler handler = handlers.get(claimedJob.jobType());
        if (handler == null) {
            markFailed(claimedJob, "no handler registered for job type " + claimedJob.jobType(), null);
            return;
        }

        try {
            // handler.handle() 执行业务 transaction，例如 authorization expiry 会释放额度并写 Outbox。
            handler.handle(claimedJob);
            // 业务成功后，worker 自己 finalize，避免 poller 提前标 DONE。
            markDone(claimedJob);
        } catch (RuntimeException exception) {
            markFailed(
                    claimedJob,
                    exception.getMessage() == null
                            ? exception.getClass().getSimpleName()
                            : exception.getMessage(),
                    exception
            );
        }
    }

    public void markRejectedForRetry(DelayJob claimedJob, RuntimeException exception) {
        markFailed(claimedJob, "worker pool rejected job", exception);
    }

    private void markDone(DelayJob claimedJob) {
        transactionOperations.executeWithoutResult(status -> {
            DelayJob job = lockCurrentLease(claimedJob);
            if (job == null) {
                return;
            }
            job.markDone(Instant.now(clock));
            delayJobRepository.updateExecutionState(job);
            log.info(
                    "delay_job_done jobId={} jobType={} aggregateType={} aggregateId={}",
                    job.id(),
                    job.jobType(),
                    job.aggregateType(),
                    job.aggregateId()
            );
        });
    }

    private void markFailed(
            DelayJob claimedJob,
            String error,
            RuntimeException exception
    ) {
        transactionOperations.executeWithoutResult(status -> {
            DelayJob job = lockCurrentLease(claimedJob);
            if (job == null) {
                return;
            }
            job.markFailed(error, Instant.now(clock), properties.maxAttempts());
            delayJobRepository.updateExecutionState(job);
            log.warn(
                    "delay_job_failed jobId={} jobType={} attempts={} status={}",
                    job.id(),
                    job.jobType(),
                    job.attempts(),
                    job.status(),
                    exception
            );
        });
    }

    private DelayJob lockCurrentLease(DelayJob claimedJob) {
        DelayJob job = delayJobRepository.findByIdForUpdate(claimedJob.id())
                .orElseThrow(() -> new IllegalStateException(
                        "claimed delay job disappeared " + claimedJob.id()
                ));
        if (job.status() != DelayJobStatus.PROCESSING
                || !job.nextAttemptAt().equals(claimedJob.nextAttemptAt())) {
            // 旧 worker 可能在 lease 过期后才返回；此时不能覆盖新 worker/recoverer 的状态。
            log.warn(
                    "delay_job_lease_changed jobId={} claimedLease={} currentStatus={} currentLease={}",
                    claimedJob.id(),
                    claimedJob.nextAttemptAt(),
                    job.status(),
                    job.nextAttemptAt()
            );
            return null;
        }
        return job;
    }

    private Map<DelayJobType, DelayJobHandler> handlersByType(List<DelayJobHandler> handlers) {
        Map<DelayJobType, DelayJobHandler> result = new EnumMap<>(DelayJobType.class);
        for (DelayJobHandler handler : handlers) {
            result.put(handler.jobType(), handler);
        }
        return result;
    }
}
