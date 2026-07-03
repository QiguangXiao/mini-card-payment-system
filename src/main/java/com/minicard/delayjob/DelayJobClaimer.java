package com.minicard.delayjob;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 批量 claim 到期 delay jobs。
 *
 * <p>关键词：任务领取, 行锁, PROCESSING lease, delay job claim,
 * FOR UPDATE SKIP LOCKED, short transaction, ジョブ取得(ジョブしゅとく),
 * 行ロック(ぎょうロック)。</p>
 *
 * <p>这个组件的 transaction boundary 故意很短：只负责用 row lock 领取任务，
 * 并立刻把 PENDING 改成 PROCESSING lease。commit 后才交给 worker pool 执行业务，
 * 避免持有 job row lock 等待业务处理。</p>
 */
@Service
@RequiredArgsConstructor
public class DelayJobClaimer {

    /** repository SQL 使用 FOR UPDATE SKIP LOCKED 领取可执行任务。 */
    private final DelayJobRepository delayJobRepository;
    /** 控制 batch size 和 lease timeout。 */
    private final DelayJobProperties properties;
    /** 注入 clock 让测试可以固定 now。 */
    private final Clock clock;

    /**
     * 领取到期任务并写入 PROCESSING lease。
     */
    // 这里的 @Transactional 必须包住 findRunnableBatchForUpdate + markProcessing update。
    // 如果查询和更新分成两个事务，其他 poller 可能在中间抢走同一批 job。
    @Transactional
    public List<DelayJob> claimDueJobs() {
        Instant now = Instant.now(clock);
        // 阶段 1：用 FOR UPDATE SKIP LOCKED 扫描可执行 job。
        // 多实例并发时，已被别的事务锁住的 row 会被跳过，而不是互相等待。
        List<DelayJob> jobs = delayJobRepository.findRunnableBatchForUpdate(
                now,
                properties.maxPerRun()
        );
        for (DelayJob job : jobs) {
            // 阶段 2：为每条 job 生成本轮 lease owner token。
            // token 是 WHO，nextAttemptAt 在 PROCESSING 下是 WHEN 到期；二者不能混用。
            // claim 后立刻 PENDING -> PROCESSING，commit 后其他 pod 就不会重复领取。
            // nextAttemptAt 在 PROCESSING 状态下临时充当 lease deadline；leaseToken 才是本轮 owner identity。
            // 如果不先写 PROCESSING lease，多实例 poller 会同时执行同一个 future business action。
            String leaseToken = UUID.randomUUID().toString();
            // 阶段 3：写入 PROCESSING lease 并在本短事务内提交。
            // worker 只收到提交后的快照，后续业务处理不会继续持有 delay_jobs row lock。
            job.markProcessing(now, properties.processingTimeoutSeconds(), leaseToken);
            delayJobRepository.updateExecutionState(job);
        }
        return jobs;
    }
}
