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
        // JsonNode 让 envelope 稳定、payload 灵活。代价是 consumer 必须显式做字段校验。
        // 如果直接把所有 event payload 做成一个大 DTO，版本演进会更僵硬，也会耦合不关心的字段。
        JsonNode payload
) {
}
