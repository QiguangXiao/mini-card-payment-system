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

/**
 * Kafka topic、consumer factory 和 DLT(error handling) 配置。
 *
 * <p>interview重点：每个 bounded context 用自己的 consumer group 和 DLT，互不影响；
 * partition key 负责单个 aggregate 内有序，consumer concurrency 负责横向处理能力。</p>
 */
@Configuration
// Kafka topic 名、DLT 名都从配置绑定进来，避免 listener/publisher 写死环境差异。
// 如果 topic string 散落在代码里，改版本或加命名空间时很容易漏改。
@EnableConfigurationProperties(KafkaTopicsProperties.class)
public class KafkaMessagingConfiguration {

    @Bean
    public NewTopic authorizationEventsTopic(KafkaTopicsProperties properties) {
        // 3 个 partitions 用来演示 consumer-group parallelism。
        // event key 使用 authorizationId，保证同一 authorization 的事件进入同一 partition。
        // 本地 Kafka 只有 1 个 broker，所以 replicas 必须是 1。
        return TopicBuilder.name(properties.authorizationEvents())
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic transactionEventsTopic(KafkaTopicsProperties properties) {
        // CardTransaction 是用户可见交易流水；单独 topic 让未来 notification 微服务
        // 可以只订阅交易事实，不需要理解 authorization 内部生命周期。
        return TopicBuilder.name(properties.transactionEvents())
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic statementEventsTopic(KafkaTopicsProperties properties) {
        // Statement events 单独 topic，未来账单通知、PDF 生成、还款提醒可以独立订阅。
        // event key 使用 creditAccountId，保证同一账户账单顺序。
        return TopicBuilder.name(properties.statementEvents())
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic repaymentEventsTopic(KafkaTopicsProperties properties) {
        // Repayment 是独立业务事实，不混入 statement topic。
        // event key 使用 creditAccountId，方便同一账户还款通知、对账投影按顺序处理。
        return TopicBuilder.name(properties.repaymentEvents())
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
    public NewTopic ledgerDeadLetterTopic(KafkaTopicsProperties properties) {
        return deadLetterTopic(properties.ledgerDeadLetter());
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<Object, Object>
            notificationKafkaListenerContainerFactory(
                    ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
                    ConsumerFactory<Object, Object> consumerFactory,
                    KafkaTemplate<Object, Object> kafkaTemplate,
                    KafkaTopicsProperties topics
    ) {
        // Notification 是独立 bounded context，它的 listener 线程数可独立于 Risk 扩缩。
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
        // Risk feature projection 属于 Risk bounded context。
        // concurrency=3 对齐 source topic partitions；再大也会因为 partition 数不足而空闲。
        return listenerFactory(
                configurer,
                consumerFactory,
                kafkaTemplate,
                topics.riskFeatureDeadLetter(),
                3
        );
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<Object, Object>
            ledgerKafkaListenerContainerFactory(
                    ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
                    ConsumerFactory<Object, Object> consumerFactory,
                    KafkaTemplate<Object, Object> kafkaTemplate,
                    KafkaTopicsProperties topics
    ) {
        // Ledger projection 独立于 Notification/Risk 失败；单独 DLT 便于只重放账本投影。
        return listenerFactory(
                configurer,
                consumerFactory,
                kafkaTemplate,
                topics.ledgerDeadLetter(),
                2
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
        // 先让 Spring Boot configurer 套用 application.yml 中的通用 consumer 配置，
        // 再覆盖本项目需要的 DLT/error handler/concurrency。否则容易丢掉 ack-mode、serializer 等默认设置。
        configurer.configure(factory, consumerFactory);

        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                // 保留 original partition，方便排查顺序问题并按 partition 做 replay。
                (record, exception) -> new TopicPartition(deadLetterTopic, record.partition())
        );
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
                recoverer,
                new FixedBackOff(1000L, 2L)
        );
        // malformed JSON 或 unsupported schema version 重试也不会自愈，
        // 所以 permanent contract failure 直接进入 DLT。
        errorHandler.addNotRetryableExceptions(EventContractException.class);

        factory.setCommonErrorHandler(errorHandler);
        // concurrency 只提高同一 consumer group 内的并行度，不改变单 partition 内顺序。
        // 如果盲目开很大但 partition 数不足，线程会空闲；如果不开，慢 consumer 会拖住整个 group。
        factory.setConcurrency(concurrency);
        return factory;
    }

    private NewTopic deadLetterTopic(String topicName) {
        // DLT partition 数和 source topic 对齐，因为 recoverer 会保留原 partition。
        return TopicBuilder.name(topicName)
                .partitions(3)
                .replicas(1)
                .build();
    }
}
