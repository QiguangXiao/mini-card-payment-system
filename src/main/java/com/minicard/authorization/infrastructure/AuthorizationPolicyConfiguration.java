package com.minicard.authorization.infrastructure;

import com.minicard.authorization.application.AuthorizationPolicyProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AuthorizationPolicyProperties.class)
public class AuthorizationPolicyConfiguration {
}
