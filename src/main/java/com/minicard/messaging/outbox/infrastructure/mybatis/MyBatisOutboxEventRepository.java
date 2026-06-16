package com.minicard.messaging.outbox.infrastructure.mybatis;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.minicard.messaging.outbox.domain.OutboxEvent;
import com.minicard.messaging.outbox.domain.OutboxEventRepository;
import com.minicard.messaging.outbox.domain.OutboxEventStatus;
import org.springframework.stereotype.Repository;

/**
 * OutboxEventRepository 的 MyBatis adapter，负责 durable message queue 的数据库访问。
 *
 * <p>面试重点：Outbox 是基础设施可靠性模式，不是业务 aggregate。
 * 它用 DB row state 表达待发布、已发布、失败重试和 DEAD。</p>
 */
@Repository
public class MyBatisOutboxEventRepository implements OutboxEventRepository {

    private final OutboxEventMapper mapper;

    public MyBatisOutboxEventRepository(OutboxEventMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void insert(OutboxEvent event) {
        mapper.insert(toRow(event));
    }

    @Override
    public Optional<OutboxEvent> findNextPublishableForUpdate(Instant now) {
        // mapper 使用 FOR UPDATE SKIP LOCKED，支持多个 publisher 实例并行领取不同事件。
        return Optional.ofNullable(mapper.findNextPublishableForUpdate(now))
                .map(this::toDomain);
    }

    @Override
    public Optional<OutboxEvent> findByIdForUpdate(UUID id) {
        return Optional.ofNullable(mapper.findByIdForUpdate(id.toString()))
                .map(this::toDomain);
    }

    @Override
    public void updateDeliveryState(OutboxEvent event) {
        // 只更新 delivery state，不改 payload；消息内容一旦入库就应保持 immutable。
        mapper.updateDeliveryState(toRow(event));
    }

    private OutboxEventRow toRow(OutboxEvent event) {
        return new OutboxEventRow(
                event.id().toString(),
                event.aggregateType(),
                event.aggregateId(),
                event.eventType(),
                event.eventVersion(),
                event.partitionKey(),
                event.payload(),
                event.status().name(),
                event.attempts(),
                event.nextAttemptAt(),
                event.createdAt(),
                event.publishedAt(),
                event.lastError()
        );
    }

    private OutboxEvent toDomain(OutboxEventRow row) {
        return OutboxEvent.restore(
                UUID.fromString(row.id()),
                row.aggregateType(),
                row.aggregateId(),
                row.eventType(),
                row.eventVersion(),
                row.partitionKey(),
                row.payload(),
                OutboxEventStatus.valueOf(row.status()),
                row.attempts(),
                row.nextAttemptAt(),
                row.createdAt(),
                row.publishedAt(),
                row.lastError()
        );
    }
}
