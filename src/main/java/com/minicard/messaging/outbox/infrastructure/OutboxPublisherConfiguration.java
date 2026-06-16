package com.minicard.messaging.outbox.infrastructure;

import com.minicard.messaging.outbox.application.OutboxPublisherProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(OutboxPublisherProperties.class)
public class OutboxPublisherConfiguration {
}
