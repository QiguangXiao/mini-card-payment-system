package com.minicard.risk.infrastructure.messaging;

import java.time.Instant;

import com.fasterxml.jackson.databind.JsonNode;
import com.minicard.messaging.event.IntegrationEvent;
import com.minicard.messaging.kafka.IntegrationEventReader;
import com.minicard.risk.application.RiskFeatureProjectionService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Risk bounded context 的 Kafka inbound adapter，把授权历史投影成风控特征。
 *
 * <p>面试重点：这是 eventually consistent projection。授权主流程不等待这个 consumer，
 * 风控特征由事件异步更新，下一笔交易再使用最新可见数据。</p>
 */
@Component
public class AuthorizationRiskFeatureListener {

    private static final String AUTHORIZATION_APPROVED = "authorization.approved";
    private static final String AUTHORIZATION_DECLINED = "authorization.declined";

    private final IntegrationEventReader eventReader;
    private final RiskFeatureProjectionService projectionService;

    public AuthorizationRiskFeatureListener(
            IntegrationEventReader eventReader,
            RiskFeatureProjectionService projectionService
    ) {
        this.eventReader = eventReader;
        this.projectionService = projectionService;
    }

    @KafkaListener(
            topics = "${messaging.topics.authorization-events}",
            groupId = "${messaging.consumers.risk-feature.group-id}",
            containerFactory = "riskFeatureKafkaListenerContainerFactory"
    )
    public void onAuthorizationDecision(ConsumerRecord<String, String> record) {
        // Listener 显式处理自己关心的事件类型；未来新增 authorization.captured 时，
        // 未订阅的 consumer 可以直接跳过，不会把“合法但不关心”的事件送进 DLT。
        IntegrationEvent event = eventReader.read(record);
        JsonNode payload = event.payload();
        if (AUTHORIZATION_APPROVED.equals(event.eventType())) {
            projectionService.project(
                    event.eventId(),
                    eventReader.requiredText(payload, "cardId"),
                    "APPROVED",
                    Instant.parse(eventReader.requiredText(payload, "approvedAt"))
            );
            return;
        }
        if (AUTHORIZATION_DECLINED.equals(event.eventType())) {
            projectionService.project(
                    event.eventId(),
                    eventReader.requiredText(payload, "cardId"),
                    "DECLINED",
                    Instant.parse(eventReader.requiredText(payload, "declinedAt"))
            );
        }
    }
}
