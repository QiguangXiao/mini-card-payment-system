package com.minicard.notification.application.delivery;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 通知投递的 scheduler / worker / 模拟 provider 配置。
 *
 * <p>关键词：投递配置, 重试策略, worker pool, delivery properties,
 * retry policy, processing lease, 配信設定(はいしんせってい)。</p>
 *
 * <p>与 OutboxProperties / DelayJobProperties 对称：把成组参数收进 typed record，
 * 启动即 fail fast，避免散落的 @Value 让默认值和校验不一致。</p>
 */
@ConfigurationProperties(prefix = "notification.delivery")
public record NotificationDeliveryProperties(
        /** 模拟 notification provider 的 base URL；本地默认回环到同一个 Spring Boot 应用。 */
        String providerBaseUrl,
        /** 是否启用投递 poller/recoverer。关掉后只创建 PENDING delivery，不实际外发。 */
        boolean enabled,
        /** poller 扫描间隔，毫秒。 */
        long fixedDelayMs,
        /** recoverer 扫描 PROCESSING 超时投递的间隔，毫秒。 */
        long recoveryFixedDelayMs,
        /** 单轮最多领取的投递数。 */
        int batchSize,
        /** PROCESSING lease 超时秒数；worker 宕机后靠 recoverer 重新放回。 */
        long processingTimeoutSeconds,
        /** 最大投递尝试次数，超过后进入 DEAD。 */
        int maxAttempts,
        /** 投递 worker 线程数。 */
        int workerPoolSize,
        /** 投递 worker queue 容量，满时触发 TaskRejectedException 并回 retry。 */
        int workerQueueCapacity,
        /** 模拟 provider 注入的延迟毫秒（用于演示 slow-call 断路和 lease/retry 关系）。 */
        long simulatedLatencyMillis,
        /** 模拟 provider 注入的失败率百分比（用于演示重试/断路/DEAD）。 */
        int simulatedFailureRatePercent
) {
    public NotificationDeliveryProperties {
        if (providerBaseUrl == null || providerBaseUrl.isBlank()) {
            throw new IllegalArgumentException("notification provider base url must not be blank");
        }
        if (fixedDelayMs <= 0
                || recoveryFixedDelayMs <= 0
                || batchSize <= 0
                || processingTimeoutSeconds <= 0
                || maxAttempts <= 0
                || workerPoolSize <= 0
                || workerQueueCapacity < 0) {
            throw new IllegalArgumentException("notification delivery properties must be positive");
        }
        if (simulatedLatencyMillis < 0
                || simulatedFailureRatePercent < 0
                || simulatedFailureRatePercent > 100) {
            throw new IllegalArgumentException("simulated provider parameters out of range");
        }
    }
}
