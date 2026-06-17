package com.minicard.messaging.outbox.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "outbox.publisher")
public record OutboxPublisherProperties(
        boolean enabled,
        long fixedDelayMs,
        long recoveryFixedDelayMs,
        int batchSize,
        long sendTimeoutMs,
        long processingTimeoutSeconds,
        int maxAttempts,
        int workerPoolSize,
        int workerQueueCapacity
) {
}
