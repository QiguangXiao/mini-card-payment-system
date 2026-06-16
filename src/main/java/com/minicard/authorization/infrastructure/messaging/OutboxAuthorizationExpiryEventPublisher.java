package com.minicard.authorization.infrastructure.messaging;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minicard.authorization.application.AuthorizationExpiryEventPublisher;
import com.minicard.authorization.domain.Authorization;
import com.minicard.messaging.event.AuthorizationExpiredEvent;
import com.minicard.messaging.event.IntegrationEventEnvelope;
import com.minicard.messaging.outbox.domain.OutboxEvent;
import com.minicard.messaging.outbox.domain.OutboxEventRepository;
import org.springframework.stereotype.Component;

/**
 * Authorization expired lifecycle event 的 Outbox adapter。
 */
@Component
public class OutboxAuthorizationExpiryEventPublisher implements AuthorizationExpiryEventPublisher {

    private static final String AGGREGATE_TYPE = "Authorization";

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public OutboxAuthorizationExpiryEventPublisher(
            OutboxEventRepository outboxEventRepository,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public void append(Authorization authorization) {
        // expired event 需要同时包含计划过期时间(expiresAt)和实际处理时间(expiredAt)。
        Instant expiresAt = authorization.expiresAt()
                .orElseThrow(() -> new IllegalArgumentException(
                        "expired authorization must have expiresAt"
                ));
        Instant expiredAt = authorization.expiredAt()
                .orElseThrow(() -> new IllegalArgumentException(
                        "expired authorization must have expiredAt"
                ));
        // 新 eventId 用于消息幂等；它和 authorizationId 不同，因为一个 aggregate 可产生多个事件。
        UUID eventId = UUID.randomUUID();
        AuthorizationExpiredEvent payload = new AuthorizationExpiredEvent(
                authorization.id(),
                authorization.cardId(),
                authorization.requestedAmount().amount().toPlainString(),
                authorization.requestedAmount().currency().getCurrencyCode(),
                expiresAt,
                expiredAt
        );
        // envelope 统一 metadata：event type/version/occurredAt/payload。
        IntegrationEventEnvelope<AuthorizationExpiredEvent> envelope =
                new IntegrationEventEnvelope<>(
                        eventId,
                        AuthorizationExpiredEvent.EVENT_TYPE,
                        AuthorizationExpiredEvent.EVENT_VERSION,
                        expiredAt,
                        payload
                );

        // insert OutboxEvent 参与 AuthorizationExpiryService 的 transaction。
        // released credit 不能在缺少 recoverable event intent 的情况下单独 commit。
        outboxEventRepository.insert(OutboxEvent.pending(
                eventId,
                AGGREGATE_TYPE,
                authorization.id().toString(),
                AuthorizationExpiredEvent.EVENT_TYPE,
                AuthorizationExpiredEvent.EVENT_VERSION,
                authorization.id().toString(),
                serialize(envelope),
                Instant.now(clock)
        ));
    }

    private String serialize(IntegrationEventEnvelope<AuthorizationExpiredEvent> envelope) {
        try {
            // 序列化失败直接抛异常，让上层 transaction rollback，避免写入不完整事件。
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("failed to serialize authorization expiry event", exception);
        }
    }
}
