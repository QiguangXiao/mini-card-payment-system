package com.minicard.delayjob;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "delay-jobs.scheduler")
public record DelayJobProperties(
        boolean enabled,
        long fixedDelayMs,
        long recoveryFixedDelayMs,
        int maxPerRun,
        int maxAttempts,
        long processingTimeoutSeconds,
        int workerPoolSize,
        int workerQueueCapacity
) {
}
