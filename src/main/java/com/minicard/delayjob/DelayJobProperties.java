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
        /** 业务 worker 线程数。 */
        int workerPoolSize,
        /** worker queue 容量，满了会触发 TaskRejectedException 并回到 retry。 */
        int workerQueueCapacity
) {
}
