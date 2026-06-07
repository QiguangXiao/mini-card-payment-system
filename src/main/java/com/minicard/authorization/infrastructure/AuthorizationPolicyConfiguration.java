package com.minicard.authorization.infrastructure;

import java.util.Currency;
import java.util.Map;
import java.util.stream.Collectors;

import com.minicard.authorization.domain.AuthorizationDecisionPolicy;
import com.minicard.authorization.domain.SingleTransactionLimitPolicy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AuthorizationPolicyProperties.class)
public class AuthorizationPolicyConfiguration {

    @Bean
    public AuthorizationDecisionPolicy authorizationDecisionPolicy(
            AuthorizationPolicyProperties properties
    ) {
        // Configuration keeps this demo policy replaceable. A production issuer
        // would usually obtain limits from account and risk bounded contexts.
        Map<Currency, java.math.BigDecimal> limits = properties.singleTransactionLimits()
                .entrySet()
                .stream()
                .collect(Collectors.toUnmodifiableMap(
                        entry -> Currency.getInstance(entry.getKey()),
                        Map.Entry::getValue
                ));
        return new SingleTransactionLimitPolicy(limits);
    }
}
