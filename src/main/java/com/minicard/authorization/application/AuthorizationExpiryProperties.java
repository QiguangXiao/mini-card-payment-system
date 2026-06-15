package com.minicard.authorization.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "authorization.expiry")
public record AuthorizationExpiryProperties(
        boolean enabled,
        long fixedDelayMs,
        int maxPerRun
) {
}
