package com.minicard.risk.infrastructure.messaging;

import com.minicard.messaging.kafka.AuthorizationEventParser;
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

    private final AuthorizationEventParser eventParser;
    private final AuthorizationRiskFeatureProjectionService projectionService;

    public AuthorizationRiskFeatureListener(
            AuthorizationEventParser eventParser,
            AuthorizationRiskFeatureProjectionService projectionService
    ) {
        this.eventParser = eventParser;
        this.projectionService = projectionService;
    }

    @KafkaListener(
            topics = "${messaging.topics.authorization-events}",
            groupId = "${messaging.consumers.risk-feature.group-id}",
            containerFactory = "riskFeatureKafkaListenerContainerFactory"
    )
    public void onAuthorizationDecided(ConsumerRecord<String, String> record) {
        // Adapter 只翻译 transport input。idempotency 和 transaction boundary
        // 属于 projection application service。
        var event = eventParser.parse(record);
        projectionService.project(new RecordAuthorizationDecisionCommand(
                event.eventId(),
                event.payload().cardId(),
                event.payload().status(),
                event.payload().decidedAt()
        ));
    }
}
