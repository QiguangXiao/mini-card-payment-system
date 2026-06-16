package com.minicard.messaging.outbox.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * OutboxEvent 的 repository port，抽象 database-backed publish queue。
 *
 * <p>面试重点：Outbox 把业务事务和 Kafka 发送解耦，但只能提供 at-least-once，
 * 因此 consumer 仍要做 idempotency。</p>
 */
public interface OutboxEventRepository {

    void insert(OutboxEvent event);

    /**
     * 领取下一条可发布事件并加 row lock，通常由 FOR UPDATE SKIP LOCKED 实现。
     */
    Optional<OutboxEvent> findNextPublishableForUpdate(Instant now);

    Optional<OutboxEvent> findByIdForUpdate(UUID id);

    void updateDeliveryState(OutboxEvent event);
}
