package com.minicard.delayjob;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Delay job worker，负责执行业务 handler，并由 worker 自己 finalize。
 *
 * <p>关键词：任务执行, lease 校验, finalize, delay job worker,
 * processing lease, retry policy, ジョブ実行(ジョブじっこう),
 * リース検証(リースけんしょう)。</p>
 *
 * <p>interview重点：claim 已经在短事务内完成；worker 只处理已经拿到 PROCESSING lease 的 job。
 * 成功后标 DONE，失败后按 retry policy 回到 PENDING 或进入 DEAD。</p>
 */
@Service
@Slf4j
public class DelayJobWorker {

    /** finalize 前会重新 FOR UPDATE 锁住 job row。 */
    private final DelayJobRepository delayJobRepository;
    /** 最大重试次数等 retry policy。 */
    private final DelayJobProperties properties;
    /** jobType -> handler 的 dispatch map。 */
    private final Map<DelayJobType, DelayJobHandler> handlers;
    /** 统一时间来源。 */
    private final Clock clock;
    /** 显式事务工具，用于拆分 handler transaction 和 finalize transaction。 */
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
            // 没有 handler 是配置错误，必须进入失败路径而不是静默跳过。
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
        // worker pool 拒绝也按失败处理，把 job 从 PROCESSING 放回 retry/DEAD。
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

    /**
     * 把 Spring 注入的 handler list 转成按 enum 查找的 map。
     *
     * <p>EnumMap 是 Java 针对 enum key 优化的 Map，实现简单且比 HashMap 更省。</p>
     */
    private Map<DelayJobType, DelayJobHandler> handlersByType(List<DelayJobHandler> handlers) {
        Map<DelayJobType, DelayJobHandler> result = new EnumMap<>(DelayJobType.class);
        for (DelayJobHandler handler : handlers) {
            result.put(handler.jobType(), handler);
        }
        return result;
    }
}
