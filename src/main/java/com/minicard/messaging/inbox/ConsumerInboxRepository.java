package com.minicard.messaging.inbox;

import java.time.Instant;
import java.util.UUID;

/**
 * 消费者 Inbox 幂等端口。
 *
 * <p>关键词：消费者幂等, Inbox, 重复消息, consumer idempotency,
 * duplicate delivery, message inbox, 消費者Inbox(しょうひしゃInbox),
 * 重複配信(じゅうふくはいしん)。</p>
 *
 * <p>Inbox 是 messaging reliability pattern，不是业务领域模型；所以它放在
 * {@code messaging.inbox}，而不是某个 bounded context 的 {@code domain} 包。</p>
 */
public interface ConsumerInboxRepository {

    /**
     * 用数据库唯一键为某个逻辑消费者 claim event。
     *
     * @return true 表示第一次消费；false 表示 duplicate delivery，可直接跳过
     */
    boolean claim(String consumerName, UUID eventId, Instant processedAt);
}
