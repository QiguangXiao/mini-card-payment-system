package com.minicard.scheduling.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "delay-jobs.scheduler")
public record DelayJobSchedulerProperties(
        boolean enabled,
        long fixedDelayMs,
        int maxPerRun,
        int maxAttempts,
        long processingTimeoutSeconds
) {
}
