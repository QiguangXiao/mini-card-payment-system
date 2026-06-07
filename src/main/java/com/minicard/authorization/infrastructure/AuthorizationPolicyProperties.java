package com.minicard.authorization.infrastructure;

import java.math.BigDecimal;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "authorization.policy")
public record AuthorizationPolicyProperties(
        Map<String, BigDecimal> singleTransactionLimits
) {
}
