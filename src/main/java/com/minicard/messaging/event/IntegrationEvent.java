package com.minicard.messaging.event;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Kafka/Outbox 共用的最小 integration event JSON 结构。
 *
 * <p>payload 保持 JsonNode，避免为每个 event type 都创建一组 Java payload class。
 * Consumer 根据 eventType 判断是否关心，再读取自己需要的字段。</p>
 */
public record IntegrationEvent(
        UUID eventId,
        String eventType,
        int eventVersion,
        Instant occurredAt,
        JsonNode payload
) {
}
