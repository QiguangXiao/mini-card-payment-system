package com.minicard.messaging.kafka;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Kafka topic 配置。
 *
 * <p>关键词：消息主题, Kafka 配置, 死信主题, Kafka topics,
 * dead letter topic, 設定バインド(せっていバインド),
 * デッドレター。</p>
 */
// topic 名通过 typed properties 绑定，避免 listener/publisher 中硬编码字符串。
// 如果环境切换只改一处 YAML，但代码里还有硬编码 topic，就会出现只发不收的隐蔽错误。
@ConfigurationProperties(prefix = "messaging.topics")
public record KafkaTopicsProperties(
        /** authorization integration events topic。 */
        String authorizationEvents,
        /** transaction/presentment integration events topic。 */
        String transactionEvents,
        /** repayment integration events topic。 */
        String repaymentEvents,
        /** notification consumer 的 dead-letter topic。 */
        String notificationDeadLetter
) {
}
