package com.minicard.messaging.outbox.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import com.minicard.messaging.outbox.domain.OutboxEvent;
import com.minicard.messaging.outbox.domain.OutboxEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 批量 claim 待发布 Outbox events。
 *
 * <p>transaction boundary 故意很短：只做 PENDING -> PROCESSING lease。
 * Kafka ack 等待发生在 worker 里，避免持有 DB row lock 等 broker/network。</p>
 */
@Service
public class OutboxClaimService {

    private final OutboxEventRepository outboxEventRepository;
    private final OutboxPublisherProperties properties;
    private final Clock clock;

    public OutboxClaimService(
            OutboxEventRepository outboxEventRepository,
            OutboxPublisherProperties properties,
            Clock clock
    ) {
        this.outboxEventRepository = outboxEventRepository;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public List<OutboxEvent> claimPublishableEvents() {
        Instant now = Instant.now(clock);
        List<OutboxEvent> events = outboxEventRepository.findPublishableBatchForUpdate(
                now,
                properties.batchSize()
        );
        for (OutboxEvent event : events) {
            // PROCESSING lease 先 commit，worker 才开始等 Kafka ack。
            // 如果 worker 宕机，recoverer 会在 lease deadline 后把 event 放回 retry。
            event.markProcessing(now, properties.processingTimeoutSeconds());
            outboxEventRepository.updateDeliveryState(event);
        }
        return events;
    }
}
