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
 * <h3>先理解 Spring Kafka listener container</h3>
 * <p>业务代码只写 {@code @KafkaListener} 方法，但真正持续运行的是 listener container。它在后台：</p>
 * <ol>
 *   <li>向 broker poll 一批 record；</li>
 *   <li>按 partition 顺序调用 listener；</li>
 *   <li>listener 正常返回后按 ack mode 推进 offset；</li>
 *   <li>listener 抛异常时交给 error handler 决定 retry 或 DLT。</li>
 * </ol>
 * <p>本类创建的 factory 就是 container 的“模板”。不同 {@code @KafkaListener} 通过
 * {@code containerFactory} 选择模板；topic 创建由 {@link KafkaTopicsConfiguration} 负责。</p>
 *
 * <h3>group、partition 和 concurrency 各自控制什么</h3>
 * <p>不同 consumer group 会各自收到同一条事件，例如 Notification 和 Ledger 都能消费
 * {@code card_transaction.posted}；同一 group 内才会分摊 partition。partition key 保证同一 aggregate
 * 的事件进入同一 partition，concurrency 只增加不同 partition 的并行处理能力，不能突破 partition 数上限。</p>
 *
 * <p>三个 factory 结构相同，只差 DLT topic 和 concurrency——刻意不合并成一个全局
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
                    // configurer 把 spring.kafka.consumer/listener 的 Boot 通用配置复制到自建 factory。
                    ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
                    // ConsumerFactory 持有 bootstrap servers、deserializer、group 等创建 KafkaConsumer 所需配置。
                    ConsumerFactory<Object, Object> consumerFactory,
                    // KafkaTemplate 在这里不是发业务事件，而是给 recoverer 发布失败 record 到 DLT。
                    KafkaTemplate<Object, Object> kafkaTemplate,
                    // topics 提供 notification DLT 名，避免在 Java 中写死环境相关字符串。
                    KafkaTopicsProperties topics,
                    // consumers 提供该 bounded context 的 listener concurrency。
                    KafkaConsumersProperties consumers
    ) {
        // Notification 是独立 bounded context，它的 listener 线程数可独立于 Risk 扩缩。
        return listenerFactory(
                configurer,                                 // 继承 Spring Boot Kafka 通用配置。
                consumerFactory,                            // 创建真正执行 poll 的 KafkaConsumer。
                kafkaTemplate,                              // retry 耗尽后发布 DLT。
                topics.notificationDeadLetter(),            // Notification 专属失败出口。
                consumers.notification().concurrency()      // Notification group 内并行线程数。
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
        // factory 不是 consumer 本身，而是 @KafkaListener container 的创建模板；应用启动后才据此创建 container。
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        // 阶段 1：先让 Spring Boot configurer 套用 application.yml 中的通用 consumer 配置，
        // 再覆盖本项目需要的 DLT/error handler/concurrency。否则容易丢掉 ack-mode、serializer 等默认设置。
        // 这里会吃到 spring.kafka.consumer.enable-auto-commit=false 和 spring.kafka.listener.ack-mode=record：
        // 关闭的是 Kafka consumer 原生后台自动提交；保留的是 Spring Kafka container 的受控自动提交。
        // listener 正常 return 后，container 自动为这条 record ack/commit offset；不是业务方法里手写 commit。
        configurer.configure(factory, consumerFactory); // 把反序列化、ack mode 等基础配置接到这个模板。

        // 阶段 2：定义“这条 record 最终处理不了时发到哪里”。DLT 不是自动修复机制，
        // 它把 poison message 留下来，避免一条坏消息永远堵住 partition，后续由人工修复/replay。
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate, // recoverer 复用 producer 能力，把原 record 和异常 headers 发到 DLT。
                // Recoverer 负责"最后失败后发到哪个 DLT"。它不是业务 retry，
                // 而是 Spring Kafka error handler 的最后一道出口。
                // 保留 original partition，方便排查顺序问题并按 partition 做 replay。
                (record, exception) -> new TopicPartition(
                        deadLetterTopic,   // 失败目的地按 bounded context 隔离。
                        record.partition() // 保留源 partition，便于按原顺序排查和 replay。
                )
        );
        // 阶段 3：把短暂 retry 与最终 DLT recoverer 组合成统一 error handler。
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
                recoverer, // retry 用尽后的最终处理器。
                // FixedBackOff(1000, 2) 表示 listener 抛异常后，container 自动等待 1s 再重试，
                // 最多做 2 次重试；仍失败时交给上面的 recoverer publish 到 DLT。
                // 所以业务 listener 不需要自己 catch 后 sleep/retry，除非是业务语义上的补偿。
                new FixedBackOff(
                        1000L, // 每次重试前固定等待 1 秒，避免立即热循环。
                        2L     // 原始调用之外最多重试 2 次，总计最多调用 listener 3 次。
                )
        );
        // malformed JSON 或 unsupported schema version 重试也不会自愈，
        // 所以 permanent contract failure 不消耗这 2 次 retry，直接进入 DLT。
        // 例如 payload 缺必填字段、时间格式错误；这些不是 DB 短暂抖动，重试同一条消息也不会变好。
        errorHandler.addNotRetryableExceptions(EventContractException.class); // 永久坏消息直接 DLT，不做无效等待。

        // 阶段 4：把 error handler 和 concurrency 装进 factory。之后 @KafkaListener 抛出的异常会被 container 接管：
        // 1. 可重试异常：按 FixedBackOff retry，同一 record 重新调用 listener。
        // 2. 不可重试或重试耗尽：DeadLetterPublishingRecoverer 发 DLT。
        // 3. DLT publish 成功后，这条原消息被视为 handled，container 才能提交/推进 offset。
        // 如果 DLT publish 也失败，offset 不应前进，否则 poison message 会丢失。
        factory.setCommonErrorHandler(errorHandler); // 把异常路径正式挂到之后创建的每个 listener container。
        // concurrency 只提高同一 consumer group 内的并行度，不改变单 partition 内顺序。
        // 如果盲目开很大但 partition 数不足，线程会空闲；如果不开，慢 consumer 会拖住整个 group。
        // 每个 listener container 线程各自 poll 分配到的 partitions；同一个 partition 在同一个 group 内
        // 同一时刻只会被一个 consumer 线程处理，因此同 partition ordering 仍成立。
        factory.setConcurrency(concurrency); // 创建多少个同 group consumer thread；不会自动增加 topic partition。
        return factory;                      // @Bean 方法把模板交给 Spring，供 containerFactory 名称引用。
    }
}
