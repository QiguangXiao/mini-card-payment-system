package com.minicard.risk.infrastructure.external;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "risk.external")
public record ExternalRiskProperties(
        long simulatedLatencyMillis,
        int failureRatePercent,
        int highRiskScoreThreshold
) {
}
