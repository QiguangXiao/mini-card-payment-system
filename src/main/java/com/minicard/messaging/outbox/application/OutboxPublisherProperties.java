package com.minicard.messaging.outbox.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "outbox.publisher")
public record OutboxPublisherProperties(
        boolean enabled,
        long fixedDelayMs,
        int batchSize,
        long sendTimeoutMs,
        int maxAttempts
) {
}
