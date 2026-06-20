package com.minicard.messaging.kafka;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Kafka topic 配置。
 *
 * <p>关键词：消息主题, Kafka 配置, 死信主题, Kafka topics,
 * dead letter topic, 設定バインド(せっていバインド),
 * デッドレター。</p>
 */
@ConfigurationProperties(prefix = "messaging.topics")
public record KafkaTopicsProperties(
        /** authorization integration events topic。 */
        String authorizationEvents,
        /** transaction/presentment integration events topic。 */
        String transactionEvents,
        /** statement integration events topic。 */
        String statementEvents,
        /** repayment integration events topic。 */
        String repaymentEvents,
        /** notification consumer 的 dead-letter topic。 */
        String notificationDeadLetter,
        /** risk feature consumer 的 dead-letter topic。 */
        String riskFeatureDeadLetter,
        /** ledger consumer 的 dead-letter topic。 */
        String ledgerDeadLetter
) {
}
