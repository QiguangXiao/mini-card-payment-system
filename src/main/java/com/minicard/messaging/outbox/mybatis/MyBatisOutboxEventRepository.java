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
    /**
     * 写入一条待发布 Outbox event。
     */
    public void insert(OutboxEvent event) {
        mapper.insert(toRow(event));
    }

    @Override
    /**
     * 锁定一批当前可发布的事件，供 claimer 写入 PROCESSING lease。
     */
    public List<OutboxEvent> findPublishableBatchForUpdate(Instant now, int limit) {
        // mapper 使用 FOR UPDATE SKIP LOCKED，支持多个 publisher 实例并行领取不同事件。
        return mapper.findPublishableBatchForUpdate(now, limit)
                .stream()
                // Stream mapping 在 repository adapter 内完成；service/worker 不接触 OutboxEventRow。
                .map(this::toDomain)
                .toList();
    }

    @Override
    /**
     * 锁定一批 lease 已超时的 PROCESSING 事件，供 recoverer 恢复。
     */
    public List<OutboxEvent> findStuckProcessingBatchForUpdate(Instant now, int limit) {
        // recovery 单独扫描 PROCESSING lease，避免正常 poller 混入故障恢复语义。
        return mapper.findStuckProcessingBatchForUpdate(now, limit)
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    /**
     * 按 id 锁定当前事件行，供 worker finalize 前重新校验 lease。
     */
    public Optional<OutboxEvent> findByIdForUpdate(UUID id) {
        return Optional.ofNullable(mapper.findByIdForUpdate(id.toString()))
                .map(this::toDomain);
    }

    @Override
    /**
     * 更新 Outbox 交付状态字段，不修改事件业务内容。
     */
    public void updateDeliveryState(OutboxEvent event) {
        // 只更新 delivery state，不改 payload；消息内容一旦入库就应保持 immutable。
        mapper.updateDeliveryState(toRow(event));
    }

    /**
     * 将 OutboxEvent domain object 转成数据库 row DTO。
     */
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

    /**
     * 将数据库 row DTO 还原成带状态机校验的 OutboxEvent。
     */
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
