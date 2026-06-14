package com.minicard.messaging.kafka;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

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

    @Bean
    public NewTopic notificationDeadLetterTopic(KafkaTopicsProperties properties) {
        return deadLetterTopic(properties.notificationDeadLetter());
    }

    @Bean
    public NewTopic riskFeatureDeadLetterTopic(KafkaTopicsProperties properties) {
        return deadLetterTopic(properties.riskFeatureDeadLetter());
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<Object, Object>
            notificationKafkaListenerContainerFactory(
                    ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
                    ConsumerFactory<Object, Object> consumerFactory,
                    KafkaTemplate<Object, Object> kafkaTemplate,
                    KafkaTopicsProperties topics
            ) {
        // Notification is a separate bounded context. Its listener currently
        // needs two threads; this can scale independently from Risk consumers.
        return listenerFactory(
                configurer,
                consumerFactory,
                kafkaTemplate,
                topics.notificationDeadLetter(),
                2
        );
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<Object, Object>
            riskFeatureKafkaListenerContainerFactory(
                    ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
                    ConsumerFactory<Object, Object> consumerFactory,
                    KafkaTemplate<Object, Object> kafkaTemplate,
                    KafkaTopicsProperties topics
            ) {
        // Risk feature projection belongs to the Risk bounded context. Three
        // threads consume all source partitions concurrently; a larger value
        // would sit idle until the topic partition count grows.
        return listenerFactory(
                configurer,
                consumerFactory,
                kafkaTemplate,
                topics.riskFeatureDeadLetter(),
                3
        );
    }

    private ConcurrentKafkaListenerContainerFactory<Object, Object> listenerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object, Object> consumerFactory,
            KafkaTemplate<Object, Object> kafkaTemplate,
            String deadLetterTopic,
            int concurrency
    ) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        configurer.configure(factory, consumerFactory);

        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                // Keep the original partition number so operations can correlate
                // ordering and replay one failed partition deliberately.
                (record, exception) -> new TopicPartition(deadLetterTopic, record.partition())
        );
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
                recoverer,
                new FixedBackOff(1000L, 2L)
        );
        // Retrying malformed JSON or an unsupported schema version cannot heal
        // the message, so permanent contract failures go directly to the DLT.
        errorHandler.addNotRetryableExceptions(EventContractException.class);

        factory.setCommonErrorHandler(errorHandler);
        factory.setConcurrency(concurrency);
        return factory;
    }

    private NewTopic deadLetterTopic(String topicName) {
        // DLT partition count matches the source topic because the recoverer
        // preserves the original partition.
        return TopicBuilder.name(topicName)
                .partitions(3)
                .replicas(1)
                .build();
    }
}
