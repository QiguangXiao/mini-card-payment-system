package com.minicard.risk.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.minicard.messaging.event.IntegrationEvent;
import com.minicard.messaging.kafka.IntegrationEventReader;
import com.minicard.risk.application.ProjectRiskFeatureCommand;
import com.minicard.risk.application.RiskFeatureProjectionService;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Risk bounded context 的 Kafka inbound adapter，把授权历史投影成风控特征。
 *
 * <p>interview重点：这是 eventually consistent projection。授权主流程不等待这个 consumer，
 * 风控特征由事件异步更新，下一笔交易再使用最新可见数据。</p>
 */
@Component
@RequiredArgsConstructor
public class AuthorizationRiskFeatureListener {

    private static final String AUTHORIZATION_APPROVED = "authorization.approved";
    private static final String AUTHORIZATION_DECLINED = "authorization.declined";

    private final IntegrationEventReader eventReader;
    private final RiskFeatureProjectionService projectionService;

    @KafkaListener(
            topics = "${messaging.topics.authorization-events}",
            groupId = "${messaging.consumers.risk-feature.group-id}",
            containerFactory = "riskFeatureKafkaListenerContainerFactory"
    )
    public void onAuthorizationDecision(ConsumerRecord<String, String> record) {
        // Listener 显式处理自己关心的事件类型；authorization.posted 等合法但无关的事件
        // 直接跳过，不会把“不感兴趣”误判成坏消息送进 DLT。
        // 如果先按 approved/declined payload 解析，再判断 eventType，合法的 posted 事件会被误送 DLT。
        IntegrationEvent event = eventReader.read(record);
        JsonNode payload = event.payload();
        if (AUTHORIZATION_APPROVED.equals(event.eventType())) {
            projectionService.project(ProjectRiskFeatureCommand.approved(
                    event.eventId(),
                    eventReader.requiredText(payload, "cardId"),
                    eventReader.requiredInstant(payload, "approvedAt")
            ));
            return;
        }
        if (AUTHORIZATION_DECLINED.equals(event.eventType())) {
            projectionService.project(ProjectRiskFeatureCommand.declined(
                    event.eventId(),
                    eventReader.requiredText(payload, "cardId"),
                    eventReader.requiredInstant(payload, "declinedAt")
            ));
        }
    }
}
