package com.minicard.scheduling.application;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.minicard.scheduling.domain.DelayJob;
import com.minicard.scheduling.domain.DelayJobRepository;
import com.minicard.scheduling.domain.DelayJobType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 领取到期 delay job，并分发给对应业务 handler 执行。
 *
 * <p>claim、business handling、final state update 故意拆成三个 transaction。
 * 这样 handler 失败时，业务写入先 rollback，然后 job attempts/lastError 再单独提交。</p>
 */
@Service
public class DelayJobService {

    private static final Logger log = LoggerFactory.getLogger(DelayJobService.class);

    private final DelayJobRepository delayJobRepository;
    private final DelayJobSchedulerProperties properties;
    private final Map<DelayJobType, DelayJobHandler> handlers;
    private final Clock clock;
    private final TransactionOperations transactionOperations;

    public DelayJobService(
            DelayJobRepository delayJobRepository,
            DelayJobSchedulerProperties properties,
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

    public boolean dispatchNext() {
        // 第一步 claim：把可运行 job 标成 PROCESSING lease，避免多个 pod 同时处理同一 job。
        DelayJob job = claimNextRunnable();
        if (job == null) {
            return false;
        }

        // 第二步 dispatch：通过 jobType 找 handler。新增任务类型时，只需要新增 handler。
        DelayJobHandler handler = handlers.get(job.jobType());
        if (handler == null) {
            markFailed(job, "no handler registered for job type " + job.jobType(), null);
            return true;
        }

        try {
            // handler.handle() 执行业务 transaction，例如 authorization expiry 会释放额度并写 Outbox。
            handler.handle(job);
            // 第三步 finalize：业务成功后把 job 标为 DONE。
            markDone(job);
        } catch (RuntimeException exception) {
            // 业务失败时 markFailed() 会记录 lastError，并按 retry policy 重新排队或转 DEAD。
            markFailed(
                    job,
                    exception.getMessage() == null
                            ? exception.getClass().getSimpleName()
                            : exception.getMessage(),
                    exception
            );
        }
        return true;
    }

    private DelayJob claimNextRunnable() {
        return transactionOperations.execute(status -> {
            Instant now = Instant.now(clock);
            // findNextRunnableForUpdate() 使用 FOR UPDATE SKIP LOCKED，
            // 多 pod 并发扫描时会跳过别人已锁住的 row。
            DelayJob job = delayJobRepository.findNextRunnableForUpdate(now).orElse(null);
            if (job == null) {
                return null;
            }
            // claim 先 commit，缩短 job row lock 时间，并让 handler 拥有自己的业务事务边界。
            job.markProcessing(now, properties.processingTimeoutSeconds());
            delayJobRepository.updateExecutionState(job);
            return job;
        });
    }

    private void markDone(DelayJob claimedJob) {
        transactionOperations.executeWithoutResult(status -> {
            // 再次 FOR UPDATE 读取 job，确保 finalize 阶段更新的是当前 DB 状态。
            DelayJob job = delayJobRepository.findByIdForUpdate(claimedJob.id())
                    .orElseThrow(() -> new IllegalStateException(
                            "claimed delay job disappeared " + claimedJob.id()
                    ));
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

    private void markFailed(DelayJob claimedJob, String error, RuntimeException exception) {
        transactionOperations.executeWithoutResult(status -> {
            // 失败记录单独提交；这能保留故障线索，而不是跟业务 transaction 一起 rollback 掉。
            DelayJob job = delayJobRepository.findByIdForUpdate(claimedJob.id())
                    .orElseThrow(() -> new IllegalStateException(
                            "claimed delay job disappeared " + claimedJob.id()
                    ));
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

    private Map<DelayJobType, DelayJobHandler> handlersByType(List<DelayJobHandler> handlers) {
        Map<DelayJobType, DelayJobHandler> result = new EnumMap<>(DelayJobType.class);
        for (DelayJobHandler handler : handlers) {
            result.put(handler.jobType(), handler);
        }
        return result;
    }
}
