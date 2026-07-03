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
 *
 * <p>Kafka 消费不是业务代码自己 while-loop poll：Spring Kafka listener container
 * 自动 poll broker、调用 {@code @KafkaListener} 方法、处理异常、按 ack-mode 提交 offset。
 * 本类就是把这些自动行为显式配置出来，方便解释 retry、DLT 和 offset commit 的边界。</p>
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
        // Notification consumer 失败只进 notification DLT。
        // 如果所有 consumer 共用一个 DLT，排查时很难判断是通知、风控还是账本投影坏了。
        return deadLetterTopic(properties.notificationDeadLetter());
    }

    @Bean
    public NewTopic riskFeatureDeadLetterTopic(KafkaTopicsProperties properties) {
        // Risk projection 的坏消息单独进 risk DLT，避免风控画像投影问题污染通知侧告警。
        return deadLetterTopic(properties.riskFeatureDeadLetter());
    }

    @Bean
    public NewTopic ledgerDeadLetterTopic(KafkaTopicsProperties properties) {
        // Ledger projection 是财务学习重点；单独 DLT 便于只重放 ledger 消息，不影响 Notification。
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
        // 这里会吃到 spring.kafka.consumer.enable-auto-commit=false 和 spring.kafka.listener.ack-mode=record：
        // 关闭的是 Kafka consumer 原生后台自动提交；保留的是 Spring Kafka container 的受控自动提交。
        // listener 正常 return 后，container 自动为这条 record ack/commit offset；不是业务方法里手写 commit。
        configurer.configure(factory, consumerFactory);

        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                // Recoverer 负责"最后失败后发到哪个 DLT"。它不是业务 retry，
                // 而是 Spring Kafka error handler 的最后一道出口。
                // 保留 original partition，方便排查顺序问题并按 partition 做 replay。
                (record, exception) -> new TopicPartition(deadLetterTopic, record.partition())
        );
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
                recoverer,
                // FixedBackOff(1000, 2) 表示 listener 抛异常后，container 自动等待 1s 再重试，
                // 最多做 2 次重试；仍失败时交给上面的 recoverer publish 到 DLT。
                // 所以业务 listener 不需要自己 catch 后 sleep/retry，除非是业务语义上的补偿。
                new FixedBackOff(1000L, 2L)
        );
        // malformed JSON 或 unsupported schema version 重试也不会自愈，
        // 所以 permanent contract failure 不消耗这 2 次 retry，直接进入 DLT。
        // 例如 payload 缺必填字段、时间格式错误；这些不是 DB 短暂抖动，重试同一条消息也不会变好。
        errorHandler.addNotRetryableExceptions(EventContractException.class);

        // setCommonErrorHandler 后，@KafkaListener 抛出的异常会被 container 接管：
        // 1. 可重试异常：按 FixedBackOff retry，同一 record 重新调用 listener。
        // 2. 不可重试或重试耗尽：DeadLetterPublishingRecoverer 发 DLT。
        // 3. DLT publish 成功后，这条原消息被视为 handled，container 才能提交/推进 offset。
        // 如果 DLT publish 也失败，offset 不应前进，否则 poison message 会丢失。
        factory.setCommonErrorHandler(errorHandler);
        // concurrency 只提高同一 consumer group 内的并行度，不改变单 partition 内顺序。
        // 如果盲目开很大但 partition 数不足，线程会空闲；如果不开，慢 consumer 会拖住整个 group。
        // 每个 listener container 线程各自 poll 分配到的 partitions；同一个 partition 在同一个 group 内
        // 同一时刻只会被一个 consumer 线程处理，因此同 partition ordering 仍成立。
        factory.setConcurrency(concurrency);
        return factory;
    }

    private NewTopic deadLetterTopic(String topicName) {
        // DLT partition 数和 source topic 对齐，因为 recoverer 会保留原 partition。
        // 如果 DLT partition 更少，发送到原 partition 可能失败，反而让 offset 无法推进。
        return TopicBuilder.name(topicName)
                .partitions(3)
                .replicas(1)
                .build();
    }
}
