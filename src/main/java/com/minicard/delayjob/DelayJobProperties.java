package com.minicard.delayjob;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * DelayJob scheduler 与 worker pool 的配置。
 *
 * <p>关键词：延迟任务配置, 重试策略, worker pool, delay job properties,
 * retry policy, processing lease, 遅延ジョブ設定(ちえんジョブせってい),
 * リトライ設定(リトライせってい)。</p>
 */
@ConfigurationProperties(prefix = "delay-jobs.scheduler")
public record DelayJobProperties(
        /** 是否启用 DelayJob poller/recoverer。 */
        boolean enabled,
        /** poller 两次扫描之间的间隔，单位毫秒。 */
        long fixedDelayMs,
        /** recoverer 扫描卡住 PROCESSING job 的间隔，单位毫秒。 */
        long recoveryFixedDelayMs,
        /** 每次最多 claim 的 job 数，控制单轮负载。 */
        int maxPerRun,
        /** 最大尝试次数，超过后进入 DEAD。 */
        int maxAttempts,
        /** PROCESSING lease 超时时间，worker 宕机后靠 recoverer 重新放回队列。 */
        long processingTimeoutSeconds,
        /** 业务 worker 线程数（内部 DB 小事务类 job，如授权过期释放额度）。 */
        int workerPoolSize,
        /** worker queue 容量，满了会触发 TaskRejectedException 并回到 retry。 */
        int workerQueueCapacity,
        /**
         * AUTO_REPAYMENT 专用 worker 线程数。自动扣款要调外部银行网关（HTTP，最长等到 read timeout），
         * 和"纯 DB 小事务"的授权过期不共池：银行 brownout 时最多钉住本池，
         * 不会拖住授权额度释放（扣款日 27 号的批量爆发也只在本池排队）。
         */
        int autoRepaymentWorkerPoolSize,
        /** AUTO_REPAYMENT worker queue 容量。 */
        int autoRepaymentWorkerQueueCapacity
) {
    public DelayJobProperties {
        // 配置类也应该 fail fast。否则 fixedDelay=0 或 maxAttempts=0 会让 scheduler/worker 行为非常怪。
        if (fixedDelayMs <= 0
                || recoveryFixedDelayMs <= 0
                || maxPerRun <= 0
                || maxAttempts <= 0
                || processingTimeoutSeconds <= 0
                || workerPoolSize <= 0
                || workerQueueCapacity < 0
                || autoRepaymentWorkerPoolSize <= 0
                || autoRepaymentWorkerQueueCapacity < 0) {
            throw new IllegalArgumentException("delay job scheduler properties must be positive");
        }
    }
}
