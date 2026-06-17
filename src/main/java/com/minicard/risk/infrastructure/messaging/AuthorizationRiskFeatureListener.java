package com.minicard.risk.infrastructure.messaging;

import com.minicard.authorization.infrastructure.messaging.AuthorizationMessageReader;
import com.minicard.authorization.infrastructure.messaging.payload.AuthorizationApprovedPayload;
import com.minicard.authorization.infrastructure.messaging.payload.AuthorizationDeclinedPayload;
import com.minicard.risk.application.projection.AuthorizationRiskFeatureProjectionService;
import com.minicard.risk.application.projection.RecordAuthorizationDecisionCommand;
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

    private final AuthorizationMessageReader messageReader;
    private final AuthorizationRiskFeatureProjectionService projectionService;

    public AuthorizationRiskFeatureListener(
            AuthorizationMessageReader messageReader,
            AuthorizationRiskFeatureProjectionService projectionService
    ) {
        this.messageReader = messageReader;
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
        String eventType = messageReader.eventType(record);
        if (AuthorizationApprovedPayload.EVENT_TYPE.equals(eventType)) {
            var event = messageReader.readApproved(record);
            var payload = event.payload();
            projectionService.project(new RecordAuthorizationDecisionCommand(
                    event.eventId(),
                    payload.cardId(),
                    "APPROVED",
                    payload.approvedAt()
            ));
            return;
        }
        if (AuthorizationDeclinedPayload.EVENT_TYPE.equals(eventType)) {
            var event = messageReader.readDeclined(record);
            var payload = event.payload();
            projectionService.project(new RecordAuthorizationDecisionCommand(
                    event.eventId(),
                    payload.cardId(),
                    "DECLINED",
                    payload.declinedAt()
            ));
        }
    }
}
