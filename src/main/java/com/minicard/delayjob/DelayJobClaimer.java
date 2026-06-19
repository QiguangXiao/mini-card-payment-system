package com.minicard.delayjob;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 批量 claim 到期 delay jobs。
 *
 * <p>这个组件的 transaction boundary 故意很短：只负责用 row lock 领取任务，
 * 并立刻把 PENDING 改成 PROCESSING lease。commit 后才交给 worker pool 执行业务，
 * 避免持有 job row lock 等待业务处理。</p>
 */
@Service
@RequiredArgsConstructor
public class DelayJobClaimer {

    private final DelayJobRepository delayJobRepository;
    private final DelayJobProperties properties;
    private final Clock clock;

    @Transactional
    public List<DelayJob> claimDueJobs() {
        Instant now = Instant.now(clock);
        List<DelayJob> jobs = delayJobRepository.findRunnableBatchForUpdate(
                now,
                properties.maxPerRun()
        );
        for (DelayJob job : jobs) {
            // claim 后立刻 PENDING -> PROCESSING，commit 后其他 pod 就不会重复领取。
            // nextAttemptAt 在 PROCESSING 状态下临时充当 lease deadline。
            job.markProcessing(now, properties.processingTimeoutSeconds());
            delayJobRepository.updateExecutionState(job);
        }
        return jobs;
    }
}
