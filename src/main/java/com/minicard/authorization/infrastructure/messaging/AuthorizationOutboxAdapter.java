package com.minicard.authorization.infrastructure.messaging;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minicard.authorization.application.AuthorizationDomainEventPublisher;
import com.minicard.authorization.domain.event.AuthorizationDomainEvent;
import com.minicard.messaging.event.IntegrationEvent;
import com.minicard.messaging.outbox.OutboxEvent;
import com.minicard.messaging.outbox.OutboxEventRepository;
import org.springframework.stereotype.Component;

/**
 * Authorization domain event 的 Outbox adapter。
 *
 * <p>Adapter 负责连接可靠投递机制：生成 eventId、序列化 envelope、写 Outbox。
 * 具体 domain event 该变成哪个 eventType/payload，交给 AuthorizationMessageMapper。</p>
 */
@Component
public class AuthorizationOutboxAdapter implements AuthorizationDomainEventPublisher {

    private static final String AGGREGATE_TYPE = "Authorization";

    private final OutboxEventRepository outboxEventRepository;
    private final AuthorizationMessageMapper messageMapper;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AuthorizationOutboxAdapter(
            OutboxEventRepository outboxEventRepository,
            AuthorizationMessageMapper messageMapper,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.outboxEventRepository = outboxEventRepository;
        this.messageMapper = messageMapper;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public void append(AuthorizationDomainEvent event) {
        // eventId 属于 outbound message，不属于 domain event。
        // 由 adapter 生成后传给 mapper，确保 envelope 和 outbox row 使用同一个幂等键。
        AuthorizationMessage message = messageMapper.map(event, UUID.randomUUID());
        IntegrationEvent envelope = new IntegrationEvent(
                message.eventId(),
                message.eventType(),
                message.eventVersion(),
                message.occurredAt(),
                message.payload()
        );

        // Outbox row 和业务状态在同一 MySQL transaction 内提交；
        // Kafka 发送由通用 outbox worker 后续完成。
        outboxEventRepository.insert(OutboxEvent.pending(
                message.eventId(),
                AGGREGATE_TYPE,
                message.aggregateId().toString(),
                message.eventType(),
                message.eventVersion(),
                message.partitionKey(),
                serialize(envelope),
                Instant.now(clock)
        ));
    }

    private String serialize(IntegrationEvent envelope) {
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("failed to serialize authorization message", exception);
        }
    }
}
