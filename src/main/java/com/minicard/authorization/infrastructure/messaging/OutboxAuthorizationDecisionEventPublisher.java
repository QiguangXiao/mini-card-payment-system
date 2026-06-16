package com.minicard.authorization.infrastructure.messaging;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minicard.authorization.application.AuthorizationDecisionEventPublisher;
import com.minicard.authorization.domain.Authorization;
import com.minicard.messaging.event.AuthorizationDecidedEvent;
import com.minicard.messaging.event.IntegrationEventEnvelope;
import com.minicard.messaging.outbox.domain.OutboxEvent;
import com.minicard.messaging.outbox.domain.OutboxEventRepository;
import org.springframework.stereotype.Component;

/**
 * Authorization outbound adapter：把 domain aggregate 转成 integration event，
 * 再写入共享 Outbox 机制等待异步发布。
 */
@Component
public class OutboxAuthorizationDecisionEventPublisher
        implements AuthorizationDecisionEventPublisher {

    private static final String AGGREGATE_TYPE = "Authorization";

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public OutboxAuthorizationDecisionEventPublisher(
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
        // 只有已经决策(decided)的 authorization 才能生成事件；PENDING 事件对下游没有业务意义。
        Instant decidedAt = authorization.decidedAt()
                .orElseThrow(() -> new IllegalArgumentException(
                        "only a decided authorization can produce an integration event"
                ));
        // eventId 是下游 consumer 做幂等去重(deduplication)的主键。
        UUID eventId = UUID.randomUUID();
        AuthorizationDecidedEvent payload = new AuthorizationDecidedEvent(
                authorization.id(),
                authorization.cardId(),
                // 金额用 decimal text，避免 JSON consumer 用 floating point 时丢失金融精度。
                authorization.requestedAmount().amount().toPlainString(),
                authorization.requestedAmount().currency().getCurrencyCode(),
                authorization.status().name(),
                authorization.declineReason().map(Enum::name).orElse(null),
                decidedAt
        );
        // envelope 是统一事件外壳：包含 event metadata + payload，方便版本化和路由。
        IntegrationEventEnvelope<AuthorizationDecidedEvent> envelope =
                new IntegrationEventEnvelope<>(
                        eventId,
                        AuthorizationDecidedEvent.EVENT_TYPE,
                        AuthorizationDecidedEvent.EVENT_VERSION,
                        decidedAt,
                        payload
                );

        // insert OutboxEvent 参与 AuthorizationService 的 transaction。
        // authorization decision、credit reservation、event intent 要么一起 commit，要么一起 rollback。
        outboxEventRepository.insert(OutboxEvent.pending(
                eventId,
                AGGREGATE_TYPE,
                authorization.id().toString(),
                AuthorizationDecidedEvent.EVENT_TYPE,
                AuthorizationDecidedEvent.EVENT_VERSION,
                authorization.id().toString(),
                serialize(envelope),
                Instant.now(clock)
        ));
    }

    private String serialize(IntegrationEventEnvelope<AuthorizationDecidedEvent> envelope) {
        try {
            // ObjectMapper 把 envelope 序列化成 JSON payload，Outbox 表只保存字符串形式的消息体。
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("failed to serialize authorization event", exception);
        }
    }
}
