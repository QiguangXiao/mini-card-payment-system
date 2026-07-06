package com.minicard.messaging.kafka;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Kafka consumer 并发配置。
 *
 * <p>关键词：消费并发, 监听容器, consumer concurrency,
 * listener container, 並列消費(へいれつしょうひ)。</p>
 *
 * <p>concurrency 和 worker pool size 一样是"处理并行度"数字，所以和
 * delay-jobs/outbox/statement 的线程数一致放在 YAML，而不是硬编码在配置类里。
 * group-id 不在这里绑定：它由各 {@code @KafkaListener} 用
 * {@code ${messaging.consumers.*.group-id}} 占位符直接消费，避免同一个值有两条绑定路径。</p>
 */
@ConfigurationProperties(prefix = "messaging.consumers")
public record KafkaConsumersProperties(
        /** notification 消费侧配置。 */
        Consumer notification,
        /** risk feature projection 消费侧配置。 */
        Consumer riskFeature,
        /** ledger projection 消费侧配置。 */
        Consumer ledger
) {

    public KafkaConsumersProperties {
        if (notification == null || riskFeature == null || ledger == null) {
            throw new IllegalArgumentException("messaging.consumers sections must be configured");
        }
    }

    /**
     * 单个 bounded context 的消费配置。
     */
    public record Consumer(
            /** listener container 并发线程数；有效上限是 topic partition 数。 */
            int concurrency
    ) {
        public Consumer {
            if (concurrency <= 0) {
                throw new IllegalArgumentException("consumer concurrency must be positive");
            }
        }
    }
}
