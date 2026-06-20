package com.minicard.messaging.outbox;

import java.time.Duration;


/**
 * Outbox 消息发布端口，由 Kafka infrastructure 实现。
 *
 * <p>关键词：消息发布端口, Kafka ack, 超时, outbox publisher,
 * Kafka acknowledgement, timeout, 発行ポート(はっこうポート),
 * タイムアウト。</p>
 */
public interface OutboxMessagePublisher {

    /**
     * 发布单个 OutboxEvent，并在 timeout 内等待 broker acknowledgement。
     *
     * <p>抛出异常会被 OutboxWorker 记录为 retry/DEAD，不在这里吞掉。</p>
     */
    void publish(OutboxEvent event, Duration timeout);
}
