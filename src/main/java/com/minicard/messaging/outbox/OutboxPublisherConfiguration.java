package com.minicard.messaging.outbox;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(OutboxPublisherProperties.class)
public class OutboxPublisherConfiguration {
}
