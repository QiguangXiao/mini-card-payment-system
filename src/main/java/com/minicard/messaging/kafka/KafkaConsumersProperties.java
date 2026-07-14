package com.minicard.messaging.kafka;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Kafka consumer 消费组与并发配置。
 *
 * <p>关键词：消费并发, 消费组, consumer concurrency, consumer group,
 * listener container, 並列消費(へいれつしょうひ)。</p>
 *
 * <p>concurrency 由各 {@code @KafkaListener} 用 {@code ${messaging.consumers.*.concurrency}}
 * 占位符直接消费；group-id 有两个读者：listener 的 {@code groupId} 占位符（消费进度归属）
 * 和本绑定类（{@link KafkaConsumerConfiguration} 用它构建 失败 group 到 DLT 的路由表）。
 * 同一个 YAML key、两个读者，来源仍然唯一；本类的绑定同时承担启动期校验，
 * 缺 section 或非法值在应用启动时暴露，而不是等第一条消息失败时才发现路由表缺项。</p>
 */
@ConfigurationProperties(prefix = "messaging.consumers")
public record KafkaConsumersProperties(
        /** notification 消费侧配置。 */
        Consumer notification
) {

    public KafkaConsumersProperties {
        // compact constructor 会在 Spring 完成配置绑定时执行；缺 section 应在启动期暴露，而不是等 listener 创建。
        if (notification == null) {
            throw new IllegalArgumentException("messaging.consumers.notification must be configured");
        }
    }

    /**
     * 单个 bounded context 的消费配置。
     */
    public record Consumer(
            /** consumer group id；也是该 context 失败消息路由到自己 DLT 的 key。 */
            String groupId,
            /** listener container 并发线程数；有效上限是 topic partition 数。 */
            int concurrency
    ) {
        public Consumer {
            // group-id 为空会让 DLT 路由表在启动期就缺 key；这里 fail-fast 而不是等消费失败时才炸。
            if (groupId == null || groupId.isBlank()) {
                throw new IllegalArgumentException("consumer group-id must be configured");
            }
            // 0 不表示“关闭消费”：它会让 container 无法创建；真正的开关应使用条件装配而不是非法并发数。
            if (concurrency <= 0) {
                throw new IllegalArgumentException("consumer concurrency must be positive");
            }
        }
    }
}
