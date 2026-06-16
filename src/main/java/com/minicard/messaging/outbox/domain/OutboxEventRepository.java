package com.minicard.messaging.outbox.domain;

import java.time.Instant;
import java.util.List;

/**
 * OutboxEvent 的 repository port，抽象 database-backed publish queue。
 *
 * <p>面试重点：Outbox 把业务事务和 Kafka 发送解耦，但只能提供 at-least-once，
 * 因此 consumer 仍要做 idempotency。</p>
 */
public interface OutboxEventRepository {

    void insert(OutboxEvent event);

    /**
     * 领取可发布事件并加 row lock，通常由 FOR UPDATE SKIP LOCKED 实现。
     */
    List<OutboxEvent> findPublishableBatchForUpdate(Instant now, int batchSize);

    void updateDeliveryState(OutboxEvent event);
}
