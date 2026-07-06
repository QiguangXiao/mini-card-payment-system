package com.minicard.messaging.kafka;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
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
 * 消费行为（retry/DLT 路由/并发）在 {@link KafkaConsumerConfiguration}。
 * 拆开的原因：生产环境 topic 通常由运维/IaC 管理，consumer 行为才随应用发布——
 * 两者的变更节奏和责任人不同，混在一个类里会让"改消费重试策略"看起来像在动集群资源。</p>
 *
 * <p>NewTopic bean 由 Spring Kafka 的 KafkaAdmin 在启动时幂等创建，本地开发不用手工建 topic；
 * topic 已存在时不会被修改，所以调整 partition 数只对新环境生效。</p>
 */
@Configuration
// Kafka topic 名、DLT 名都从配置绑定进来，避免 listener/publisher 写死环境差异。
// 如果 topic string 散落在代码里，改版本或加命名空间时很容易漏改。
@EnableConfigurationProperties(KafkaTopicsProperties.class)
public class KafkaTopicsConfiguration {

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

    private NewTopic deadLetterTopic(String topicName) {
        // DLT partition 数和 source topic 对齐，因为 recoverer 会保留原 partition。
        // 如果 DLT partition 更少，发送到原 partition 可能失败，反而让 offset 无法推进。
        return TopicBuilder.name(topicName)
                .partitions(3)
                .replicas(1)
                .build();
    }
}
