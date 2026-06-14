package com.minicard.messaging.outbox.infrastructure.mybatis;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.minicard.messaging.outbox.domain.OutboxEvent;
import com.minicard.messaging.outbox.domain.OutboxEventRepository;
import com.minicard.messaging.outbox.domain.OutboxEventStatus;
import org.springframework.stereotype.Repository;

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
    public List<OutboxEvent> findPublishableBatchForUpdate(Instant now, int batchSize) {
        return mapper.findPublishableBatchForUpdate(now, batchSize).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public void updateDeliveryState(OutboxEvent event) {
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
