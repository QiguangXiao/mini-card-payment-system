package com.minicard.statement.infrastructure;

import com.minicard.statement.application.StatementBatchProperties;
import com.minicard.statement.application.StatementPolicyProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({
        StatementPolicyProperties.class,
        StatementBatchProperties.class
})
public class StatementPolicyConfiguration {
}
