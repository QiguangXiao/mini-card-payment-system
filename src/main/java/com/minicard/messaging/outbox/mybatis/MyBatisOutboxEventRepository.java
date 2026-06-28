package com.minicard.messaging.outbox.mybatis;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.minicard.messaging.outbox.OutboxEvent;
import com.minicard.messaging.outbox.OutboxEventRepository;
import com.minicard.messaging.outbox.OutboxEventStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * OutboxEventRepository 的 MyBatis adapter，负责 durable message queue 的数据库访问。
 *
 * <p>interview重点：Outbox 是基础设施可靠性模式，不是业务 aggregate。
 * 它用 DB row state 表达待发布、已发布、失败重试和 DEAD。</p>
 */
@Repository
@RequiredArgsConstructor
public class MyBatisOutboxEventRepository implements OutboxEventRepository {

    private final OutboxEventMapper mapper;

    @Override
    public void insert(OutboxEvent event) {
        mapper.insert(toRow(event));
    }

    @Override
    public List<OutboxEvent> findPublishableBatchForUpdate(Instant now, int limit) {
        // mapper 使用 FOR UPDATE SKIP LOCKED，支持多个 publisher 实例并行领取不同事件。
        return mapper.findPublishableBatchForUpdate(now, limit)
                .stream()
                // Stream mapping 在 repository adapter 内完成；service/worker 不接触 OutboxEventRow。
                .map(this::toDomain)
                .toList();
    }

    @Override
    public List<OutboxEvent> findStuckProcessingBatchForUpdate(Instant now, int limit) {
        // recovery 单独扫描 PROCESSING lease，避免正常 poller 混入故障恢复语义。
        return mapper.findStuckProcessingBatchForUpdate(now, limit)
                .stream()
                .map(this::toDomain)
                .toList();
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
                event.leaseToken(),
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
                row.leaseToken(),
                row.createdAt(),
                row.publishedAt(),
                row.lastError()
        );
    }
}
