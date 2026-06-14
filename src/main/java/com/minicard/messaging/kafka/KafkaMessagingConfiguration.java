package com.minicard.messaging.kafka;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
@EnableConfigurationProperties(KafkaTopicsProperties.class)
public class KafkaMessagingConfiguration {

    @Bean
    public NewTopic authorizationEventsTopic(KafkaTopicsProperties properties) {
        // Three partitions demonstrate consumer-group parallelism. Events use
        // authorizationId as their key, preserving order for one authorization.
        // Local Kafka has one broker, so replication factor must be one.
        return TopicBuilder.name(properties.authorizationEvents())
                .partitions(3)
                .replicas(1)
                .build();
    }
}
