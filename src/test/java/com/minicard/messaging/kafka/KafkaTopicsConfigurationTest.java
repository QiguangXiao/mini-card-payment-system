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
    // 测试目的：钉住 DeadLetterPublishingRecoverer 稳定保留源 partition 的项目策略——
    // 每个 DLT 的 partition 数不少于任何源 topic；否则 recoverer 在确认目标 partition
    // 不存在时会退化为 producer 自动选 partition，排查/replay 失去稳定对应关系。
    // 谁把源 topic 扩到 6 个 partition 而忘了同步 DLT，这里会先失败。
    void deadLetterTopicsCoverEverySourceTopicPartition() {
        contextRunner.run(context -> {
            KafkaTopicsProperties properties = Binder.get(context.getEnvironment())
                    .bind("messaging.topics", Bindable.of(KafkaTopicsProperties.class))
                    .orElseThrow(() -> new AssertionError("messaging.topics did not bind"));
            KafkaTopicsConfiguration configuration = new KafkaTopicsConfiguration();

            List<NewTopic> sourceTopics = List.of(
                    configuration.authorizationEventsTopic(properties),
                    configuration.transactionEventsTopic(properties)
            );
            List<NewTopic> deadLetterTopics = List.of(
                    configuration.notificationDeadLetterTopic(properties)
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
