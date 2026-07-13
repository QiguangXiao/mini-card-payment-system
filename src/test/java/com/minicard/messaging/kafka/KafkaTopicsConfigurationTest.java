package com.minicard.messaging.kafka;

import java.util.List;

import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaTopicsConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            // 只加载真实 application.yml 的 topic 名绑定，不连接任何 broker。
            .withInitializer(new ConfigDataApplicationContextInitializer());

    @Test
    // 测试目的：钉住 DeadLetterPublishingRecoverer 保留源 partition 的硬性前提——
    // 每个 DLT 的 partition 数必须不少于任何源 topic，否则源 partition N 的失败消息
    // 无法发布到 DLT partition N，DLT publish 失败会让 offset 无法推进。
    // 谁把源 topic 扩到 6 个 partition 而忘了同步 DLT，这里会先失败。
    void deadLetterTopicsCoverEverySourceTopicPartition() {
        contextRunner.run(context -> {
            KafkaTopicsProperties properties = Binder.get(context.getEnvironment())
                    .bind("messaging.topics", Bindable.of(KafkaTopicsProperties.class))
                    .orElseThrow(() -> new AssertionError("messaging.topics did not bind"));
            KafkaTopicsConfiguration configuration = new KafkaTopicsConfiguration();

            List<NewTopic> sourceTopics = List.of(
                    configuration.authorizationEventsTopic(properties),
                    configuration.transactionEventsTopic(properties),
                    configuration.statementEventsTopic(properties),
                    configuration.repaymentEventsTopic(properties)
            );
            List<NewTopic> deadLetterTopics = List.of(
                    configuration.notificationDeadLetterTopic(properties),
                    configuration.riskFeatureDeadLetterTopic(properties),
                    configuration.ledgerDeadLetterTopic(properties)
            );

            for (NewTopic deadLetterTopic : deadLetterTopics) {
                for (NewTopic sourceTopic : sourceTopics) {
                    assertThat(deadLetterTopic.numPartitions())
                            .as("%s partitions must cover %s partitions",
                                    deadLetterTopic.name(), sourceTopic.name())
                            .isGreaterThanOrEqualTo(sourceTopic.numPartitions());
                }
            }
        });
    }
}
