package com.minicard.risk.application;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "risk")
public record RiskProperties(
        Local local,
        External external
) {

    public record Local(
            long velocityWindowSeconds,
            int maxAuthorizationsPerWindow,
            Map<String, BigDecimal> highRiskAmountThresholds,
            Set<String> blockedMerchantIds
    ) {
    }

    public record External(
            String baseUrl,
            long simulatedLatencyMillis,
            int failureRatePercent,
            int highRiskScoreThreshold
    ) {
    }
}
