package com.minicard.messaging.kafka;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.kafka.listener.ListenerExecutionFailedException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KafkaConsumerConfigurationTest {

    private static final Map<String, String> DLT_BY_GROUP_ID = Map.of(
            "mini-card-notification-v1", "mini-card.notification.dlt.v1"
    );

    @Test
    // 测试目的：DLT 必须跟随失败的消费组，不根据 source topic 猜消费职责。
    void routesFailureToTheFailingConsumerGroupsDeadLetterTopic() {
        Exception notificationFailure = new ListenerExecutionFailedException(
                "listener failed",
                "mini-card-notification-v1",
                new RuntimeException("db connection unavailable")
        );

        assertThat(KafkaConsumerConfiguration.resolveDeadLetterTopic(
                DLT_BY_GROUP_ID, notificationFailure))
                .isEqualTo("mini-card.notification.dlt.v1");
    }

    @Test
    // 测试目的：未知 group 不允许猜 DLT。抛异常让 DLT publish 失败、offset 不推进，
    // 消息留在原 partition 表现为 lag 告警，而不是静默发进别的 context 的死信队列。
    void refusesToGuessDeadLetterTopicForUnknownGroup() {
        Exception unknownGroupFailure = new ListenerExecutionFailedException(
                "listener failed",
                "some-future-group-without-dlt",
                new RuntimeException("boom")
        );

        assertThatThrownBy(() ->
                KafkaConsumerConfiguration.resolveDeadLetterTopic(DLT_BY_GROUP_ID, unknownGroupFailure))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no dead-letter topic mapping");
    }

    @Test
    // 测试目的：不带 groupId 的异常（非 listener 调用路径）同样 fail loud，不做默认路由。
    void refusesToGuessDeadLetterTopicWithoutGroupInformation() {
        assertThatThrownBy(() ->
                KafkaConsumerConfiguration.resolveDeadLetterTopic(
                        DLT_BY_GROUP_ID, new RuntimeException("no group context")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no dead-letter topic mapping");
    }
}
