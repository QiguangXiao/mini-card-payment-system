package com.minicard.risk.infrastructure;

import java.time.Clock;
import java.time.Duration;
import java.util.Currency;
import java.util.Map;
import java.util.stream.Collectors;

import com.minicard.authorization.domain.Money;
import com.minicard.risk.domain.LocalRiskPolicy;
import com.minicard.risk.domain.RiskVelocityRepository;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(RiskPolicyProperties.class)
public class RiskConfiguration {

    @Bean
    public LocalRiskPolicy localRiskPolicy(
            RiskVelocityRepository velocityRepository,
            Clock clock,
            RiskPolicyProperties properties
    ) {
        return new LocalRiskPolicy(
                velocityRepository,
                clock,
                Duration.ofSeconds(properties.velocityWindowSeconds()),
                properties.maxAuthorizationsPerWindow(),
                toMoneyThresholds(properties.highRiskAmountThresholds()),
                properties.blockedMerchantIds()
        );
    }

    private Map<Currency, Money> toMoneyThresholds(Map<String, java.math.BigDecimal> thresholds) {
        return thresholds.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(
                        entry -> Currency.getInstance(entry.getKey()),
                        entry -> new Money(entry.getValue(), Currency.getInstance(entry.getKey()))
                ));
    }
}
