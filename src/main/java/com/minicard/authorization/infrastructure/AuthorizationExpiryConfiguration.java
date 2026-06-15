package com.minicard.authorization.infrastructure;

import com.minicard.authorization.application.AuthorizationExpiryProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AuthorizationExpiryProperties.class)
public class AuthorizationExpiryConfiguration {
}
