package com.minicard.risk.infrastructure;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "risk.local")
public record RiskPolicyProperties(
        long velocityWindowSeconds,
        int maxAuthorizationsPerWindow,
        Map<String, BigDecimal> highRiskAmountThresholds,
        Set<String> blockedMerchantIds
) {
}
