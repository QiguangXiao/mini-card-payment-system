package com.minicard.statement.application;

import java.math.BigDecimal;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "statement.policy")
public record StatementPolicyProperties(
        BigDecimal minimumPaymentRate,
        Map<String, BigDecimal> minimumPaymentFloors
) {
}
