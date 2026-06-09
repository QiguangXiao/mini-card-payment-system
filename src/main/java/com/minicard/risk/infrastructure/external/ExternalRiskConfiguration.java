package com.minicard.risk.infrastructure.external;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ExternalRiskProperties.class)
public class ExternalRiskConfiguration {
}
