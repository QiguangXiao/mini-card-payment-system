package com.minicard.messaging.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Outbox publisher 配置。
 *
 * <p>关键词：Outbox 配置, 发布重试, worker pool, outbox properties,
 * publisher retry, processing lease, アウトボックス設定(アウトボックスせってい),
 * 発行リトライ(はっこうリトライ)。</p>
 */
@ConfigurationProperties(prefix = "outbox.publisher")
public record OutboxProperties(
        /** 是否启用 Outbox poller/recoverer。 */
        boolean enabled,
        /** poller 发布扫描间隔，单位毫秒。 */
        long fixedDelayMs,
        /** recoverer 扫描 PROCESSING 超时事件的间隔。 */
        long recoveryFixedDelayMs,
        /** 单轮最多领取事件数。 */
        int batchSize,
        /** 等待 Kafka acknowledgement 的超时时间。 */
        long sendTimeoutMs,
        /** PROCESSING lease 超时时间。 */
        long processingTimeoutSeconds,
        /** 最大发布尝试次数，超过后进入 DEAD。 */
        int maxAttempts,
        /** Kafka publish worker 线程数。 */
        int workerPoolSize,
        /** publish worker queue 容量。 */
        int workerQueueCapacity
) {
}
