package com.minicard.delayjob.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import com.minicard.delayjob.domain.DelayJob;
import com.minicard.delayjob.domain.DelayJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 恢复长期停留在 PROCESSING 的 delay jobs。
 *
 * <p>PROCESSING 是 lease，不是永久状态。worker/pod 宕机时，recoverer 会把超时任务
 * 重新放回 retry 状态或转 DEAD，保证 database-backed queue 不会卡死。</p>
 */
@Component
@ConditionalOnProperty(
        prefix = "delay-jobs.scheduler",
        name = "enabled",
        havingValue = "true"
)
public class StuckDelayJobRecoverer {

    private static final Logger log = LoggerFactory.getLogger(StuckDelayJobRecoverer.class);

    private final DelayJobRepository delayJobRepository;
    private final DelayJobSchedulerProperties properties;
    private final Clock clock;

    public StuckDelayJobRecoverer(
            DelayJobRepository delayJobRepository,
            DelayJobSchedulerProperties properties,
            Clock clock
    ) {
        this.delayJobRepository = delayJobRepository;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(
            fixedDelayString = "${delay-jobs.scheduler.recovery-fixed-delay-ms:5000}",
            scheduler = "delayJobTaskScheduler"
    )
    @Transactional
    public void recoverStuckJobs() {
        Instant now = Instant.now(clock);
        List<DelayJob> jobs = delayJobRepository.findStuckProcessingBatchForUpdate(
                now,
                properties.maxPerRun()
        );
        for (DelayJob job : jobs) {
            // 超时 PROCESSING 按一次失败处理；超过 maxAttempts 后进入 DEAD，避免无限重试坏任务。
            job.markProcessingTimedOut(now, properties.maxAttempts());
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
