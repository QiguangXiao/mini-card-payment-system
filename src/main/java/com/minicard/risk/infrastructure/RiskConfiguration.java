package com.minicard.risk.infrastructure;

import com.minicard.risk.application.RiskProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(RiskProperties.class)
public class RiskConfiguration {
}
