package com.minicard.risk.infrastructure.messaging;

import java.util.Set;

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

    // 只有"独立于历史画像自身的真实风险拒绝"才计入 decline rate。其余一律不投影。
    // 关键排除项（counterfactual）：
    //  - RISK_HISTORICAL_PROFILE：投影自己的输出。计入会形成 越拒→拒绝率更高→越拒 的自我强化环，
    //    再叠加终身累计不衰减，会把一张卡永久 brick。
    //  - RISK_EXTERNAL_UNAVAILABLE：外部风控宕机时的 fail-closed，是基础设施故障而非对卡的风险判定；
    //    计入会让一次供应商 brownout 把大量卡的历史拒绝率一起拉高。
    //  - 额度/卡生命周期等运营类拒绝（INSUFFICIENT_AVAILABLE_CREDIT、CARD_NOT_FOUND、CARD_EXPIRED…）：
    //    不是欺诈信号；把"刷爆额度"误当风险会无辜封死正常高消费用户。
    private static final Set<String> RISK_PROFILE_DECLINE_REASONS = Set.of(
            "RISK_VELOCITY_EXCEEDED",
            "RISK_HIGH_AMOUNT",
            "RISK_GEOLOCATION_MISMATCH",
            "RISK_BLOCKED_MERCHANT",
            "RISK_EXTERNAL_DECLINED"
    );

    private final IntegrationEventReader eventReader;
    private final RiskFeatureProjectionService projectionService;

    // @KafkaListener 声明 topic/group/concurrency 三件事：
    // topic 决定读哪里，groupId 决定消费进度归属（也是失败消息路由到 risk DLT 的 key，
    // 见 KafkaConsumerConfiguration），concurrency 决定本 container 的并行线程数。
    // 如果多个 listener 复用错误的 groupId，不仅互相抢消费进度，失败消息还会进错 DLT。
    @KafkaListener(
            topics = "${messaging.topics.authorization-events}",
            groupId = "${messaging.consumers.risk-feature.group-id}",
            concurrency = "${messaging.consumers.risk-feature.concurrency}"
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
            // 先按 declineReason 过滤：非风险拒绝与画像自身造成的拒绝都不计入，断开自我强化环。
            // 这些事件仍被正常消费/ack，只是不改变 projection，因此重复投递依然幂等(no-op)。
            String declineReason = eventReader.requiredText(payload, "declineReason");
            if (!RISK_PROFILE_DECLINE_REASONS.contains(declineReason)) {
                return;
            }
            projectionService.project(ProjectRiskFeatureCommand.declined(
                    event.eventId(),
                    eventReader.requiredText(payload, "cardId"),
                    eventReader.requiredInstant(payload, "declinedAt")
            ));
        }
    }
}
