package com.minicard.messaging.outbox;

/**
 * Outbox event 发布状态。
 *
 * <p>关键词：Outbox 状态, 消息发布, 发布租约, outbox status,
 * message publication, processing lease, アウトボックス状態(アウトボックスじょうたい),
 * 発行リース(はっこうリース)。</p>
 */
public enum OutboxEventStatus {
    /** 等待发布。 */
    PENDING,
    /** 已被 publisher worker 领取，nextAttemptAt 临时作为 lease deadline。 */
    PROCESSING,
    /** Kafka broker 已 ack。 */
    PUBLISHED,
    /** 超过最大重试次数，需要人工处理。 */
    DEAD
}
