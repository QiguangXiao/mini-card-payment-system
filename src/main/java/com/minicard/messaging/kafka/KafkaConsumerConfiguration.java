package com.minicard.messaging.kafka;

import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Kafka consumer 容器配置：per-context listener factory、retry(error handler) 和 DLT 路由。
 *
 * <p>关键词：消费容器, 死信路由, 重试策略, listener container factory,
 * DLT routing, retry policy, 消費設定(しょうひせってい)。</p>
 *
 * <p>interview重点：每个 bounded context 用自己的 consumer group 和 DLT，互不影响；
 * partition key 负责单个 aggregate 内有序，consumer concurrency 负责横向处理能力。</p>
 *
 * <p>Kafka 消费不是业务代码自己 while-loop poll：Spring Kafka listener container
 * 自动 poll broker、调用 {@code @KafkaListener} 方法、处理异常、按 ack-mode 提交 offset。
 * 本类就是把这些自动行为显式配置出来，方便解释 retry、DLT 和 offset commit 的边界。
 * topic 本身的声明在 {@link KafkaTopicsConfiguration}，与消费行为分开维护。</p>
 *
 * <p>三个 factory 结构完全相同，只差 DLT topic 和 concurrency——刻意不合并成一个全局
 * factory：DeadLetterPublishingRecoverer 的默认约定是"源 topic + .DLT 后缀"，用默认约定
 * 一个 factory 就够；这里换成显式 per-context DLT 名 + 独立 concurrency，代价是每个
 * context 多一个 bean，换来失败隔离和扩缩粒度都落在 bounded context 边界上。</p>
 */
@Configuration
// concurrency 从 messaging.consumers.* 绑定进来，和 worker pool size 一样放 YAML，
// 调整并行度不需要改代码。group-id 仍由 @KafkaListener 占位符消费。
@EnableConfigurationProperties(KafkaConsumersProperties.class)
public class KafkaConsumerConfiguration {

    @Bean
    public ConcurrentKafkaListenerContainerFactory<Object, Object>
            notificationKafkaListenerContainerFactory(
                    ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
                    ConsumerFactory<Object, Object> consumerFactory,
                    KafkaTemplate<Object, Object> kafkaTemplate,
                    KafkaTopicsProperties topics,
                    KafkaConsumersProperties consumers
    ) {
        // Notification 是独立 bounded context，它的 listener 线程数可独立于 Risk 扩缩。
        return listenerFactory(
                configurer,
                consumerFactory,
                kafkaTemplate,
                topics.notificationDeadLetter(),
                consumers.notification().concurrency()
        );
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<Object, Object>
            riskFeatureKafkaListenerContainerFactory(
                    ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
                    ConsumerFactory<Object, Object> consumerFactory,
                    KafkaTemplate<Object, Object> kafkaTemplate,
                    KafkaTopicsProperties topics,
                    KafkaConsumersProperties consumers
    ) {
        // Risk feature projection 属于 Risk bounded context。
        // 默认 concurrency=3 对齐 source topic partitions；再大也会因为 partition 数不足而空闲。
        return listenerFactory(
                configurer,
                consumerFactory,
                kafkaTemplate,
                topics.riskFeatureDeadLetter(),
                consumers.riskFeature().concurrency()
        );
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<Object, Object>
            ledgerKafkaListenerContainerFactory(
                    ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
                    ConsumerFactory<Object, Object> consumerFactory,
                    KafkaTemplate<Object, Object> kafkaTemplate,
                    KafkaTopicsProperties topics,
                    KafkaConsumersProperties consumers
    ) {
        // Ledger projection 独立于 Notification/Risk 失败；单独 DLT 便于只重放账本投影。
        return listenerFactory(
                configurer,
                consumerFactory,
                kafkaTemplate,
                topics.ledgerDeadLetter(),
                consumers.ledger().concurrency()
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
}
