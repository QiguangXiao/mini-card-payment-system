package com.minicard.repayment.infrastructure;

import com.minicard.repayment.application.AutoDebitProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AutoDebitProperties.class)
public class AutoDebitConfiguration {
}
