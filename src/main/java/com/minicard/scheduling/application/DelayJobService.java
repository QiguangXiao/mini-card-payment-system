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
 * Claims due delay jobs and delegates each job to its business handler.
 *
 * <p>Claiming, business handling, and final state updates are intentionally
 * separate transactions. A handler failure must roll back business writes before
 * the job failure counter is committed.</p>
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
        DelayJob job = claimNextRunnable();
        if (job == null) {
            return false;
        }

        DelayJobHandler handler = handlers.get(job.jobType());
        if (handler == null) {
            markFailed(job, "no handler registered for job type " + job.jobType(), null);
            return true;
        }

        try {
            handler.handle(job);
            markDone(job);
        } catch (RuntimeException exception) {
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
            DelayJob job = delayJobRepository.findNextRunnableForUpdate(now).orElse(null);
            if (job == null) {
                return null;
            }
            // The claim commits before business handling. That keeps the job
            // row lock short and lets the handler use its own transaction.
            job.markProcessing(now, properties.processingTimeoutSeconds());
            delayJobRepository.updateExecutionState(job);
            return job;
        });
    }

    private void markDone(DelayJob claimedJob) {
        transactionOperations.executeWithoutResult(status -> {
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
