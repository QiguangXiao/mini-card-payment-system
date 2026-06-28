package com.minicard.messaging.outbox.mybatis;

import java.time.Instant;

/**
 * outbox_events 表的 MyBatis row DTO。
 *
 * <p>关键词：Outbox 行, 消息载荷, 发布状态, outbox row,
 * event payload, publication status, アウトボックス行(アウトボックスぎょう),
 * メッセージ本文(メッセージほんぶん)。</p>
 */
public record OutboxEventRow(
        /** outbox event id。 */
        String id,
        /** 聚合类型，例如 Authorization/Statement。 */
        String aggregateType,
        /** 聚合 id，用于追踪事件来源。 */
        String aggregateId,
        /** integration event 类型。 */
        String eventType,
        /** event schema version。 */
        Integer eventVersion,
        /** Kafka partition key。 */
        String partitionKey,
        /** JSON payload。 */
        String payload,
        /** OutboxEventStatus 字符串。 */
        String status,
        /** 已发布尝试次数。 */
        Integer attempts,
        /** 下次可发布时间；PROCESSING 时表示 lease deadline。 */
        Instant nextAttemptAt,
        /** PROCESSING lease 的 owner token；非 PROCESSING 时为空。 */
        String leaseToken,
        /** 创建时间。 */
        Instant createdAt,
        /** 成功发布时间。 */
        Instant publishedAt,
        /** 最近一次失败原因。 */
        String lastError
) {
}
