package com.minicard.messaging.kafka;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Kafka topic 声明（业务 topic + 各 context 的 DLT）。
 *
 * <p>关键词：主题声明, 死信主题, topic provisioning, dead letter topic,
 * トピック定義(トピックていぎ)。</p>
 *
 * <p>本类只回答"集群上应该存在哪些 topic、几个 partition"，属于资源供给；
 * 消费行为（retry/DLT 路由）在 {@link KafkaConsumerConfiguration}，并发在各 {@code @KafkaListener}。
 * 拆开的原因：生产环境 topic 通常由运维/IaC 管理，consumer 行为才随应用发布——
 * 两者的变更节奏和责任人不同，混在一个类里会让"改消费重试策略"看起来像在动集群资源。</p>
 *
 * <p>NewTopic bean 由 Spring Kafka 的 KafkaAdmin 在启动时幂等处理，本地开发不用手工建 topic。
 * topic 已存在时不会重复创建；声明的 partition 数更大时 KafkaAdmin 可以扩容，但 Kafka 不支持缩小
 * partition，已有 topic 的 replication factor 也不会靠这段 builder 自动重排。生产仍应由 IaC 管理变更。</p>
 */
@Configuration
// Kafka topic 名、DLT 名都从 KafkaTopicsProperties（主类 @ConfigurationPropertiesScan 注册）绑定进来，
// 避免 listener/publisher 写死环境差异。如果 topic string 散落在代码里，改版本或加命名空间时很容易漏改。
public class KafkaTopicsConfiguration {

    @Bean
    public NewTopic authorizationEventsTopic(KafkaTopicsProperties properties) {
        // 3 个 partitions 用来演示 consumer-group parallelism。
        // event key 使用 authorizationId，保证同一 authorization 的事件进入同一 partition。
        // 本地 Kafka 只有 1 个 broker，所以 replicas 必须是 1。
        return TopicBuilder.name(properties.authorizationEvents()) // name 来自 YAML，发布者和 listener 共用同一来源。
                .partitions(3)  // 一条 record 只进入其中一个 partition；partition 是 group 并行度上限。
                .replicas(1)    // 副本数不能超过 broker 数；本地只有一个 broker。
                .build();       // 只构造 NewTopic 描述，真正创建由 KafkaAdmin 在启动阶段完成。
    }

    @Bean
    public NewTopic transactionEventsTopic(KafkaTopicsProperties properties) {
        // CardTransaction 是用户可见交易流水；单独 topic 让未来 notification 微服务
        // 可以只订阅交易事实，不需要理解 authorization 内部生命周期。
        return TopicBuilder.name(properties.transactionEvents()) // card_transaction.* 的发送目的地。
                .partitions(3)  // 允许同 group 最多三个 consumer 并行处理不同 partition。
                .replicas(1)    // 本地学习环境单副本；生产通常提高副本数容忍 broker 故障。
                .build();       // 返回 NewTopic bean 给 KafkaAdmin。
    }

    @Bean
    public NewTopic repaymentEventsTopic(KafkaTopicsProperties properties) {
        // Repayment 是独立业务事实，不混入 statement topic。
        // event key 使用 creditAccountId，方便同一账户还款通知、对账投影按顺序处理。
        return TopicBuilder.name(properties.repaymentEvents()) // repayment.* 事件 topic。
                .partitions(3)  // 不代表启动三个 listener；实际并发还由 consumer factory 决定。
                .replicas(1)    // replication factor，和 consumer concurrency 是两个完全不同的概念。
                .build();       // 结束 builder，生成资源声明。
    }

    @Bean
    public NewTopic notificationDeadLetterTopic(KafkaTopicsProperties properties) {
        // Notification consumer 失败只进 notification DLT。
        return deadLetterTopic(properties.notificationDeadLetter());
    }

    private NewTopic deadLetterTopic(String topicName) {
        // DLT partition 数和 source topic 对齐，因为 recoverer 会保留原 partition。
        // 如果 DLT partition 更少，recoverer 可能退化为 producer 自动选 partition，失去稳定对应关系。
        return TopicBuilder.name(topicName) // 每个 bounded context 传入自己的 DLT 名。
                .partitions(3)  // 覆盖源 partition 0..2，保留排查和 replay 时的对应关系。
                .replicas(1)    // 本地 broker 限制；不表达 DLT 的消费并发。
                .build();       // 生成供 KafkaAdmin 创建的 NewTopic bean。
    }
}
