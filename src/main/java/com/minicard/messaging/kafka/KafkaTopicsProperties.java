package com.minicard.messaging.kafka;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "messaging.topics")
public record KafkaTopicsProperties(
        String authorizationEvents
) {
}
