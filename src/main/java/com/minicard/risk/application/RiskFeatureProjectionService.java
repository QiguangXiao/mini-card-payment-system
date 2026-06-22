package com.minicard.risk.application;

import java.time.Clock;
import java.time.Instant;

import com.minicard.messaging.inbox.ConsumerInboxRepository;
import com.minicard.risk.infrastructure.mybatis.CardRiskFeatureProjectionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Risk Kafka consumer 的投影更新服务。
 *
 * <p>保留 Inbox idempotency 学习点，但不再额外包一层 projection repository port。</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RiskFeatureProjectionService {

    public static final String CONSUMER_NAME = "risk-feature-v1";

    private final ConsumerInboxRepository inboxRepository;
    private final CardRiskFeatureProjectionMapper mapper;
    private final Clock clock;

    @Transactional
    public void project(ProjectRiskFeatureCommand command) {
        // Risk projection 是 eventually consistent，可重放但不能重复累计同一 event。
        // 如果没有 Inbox，Kafka redelivery 会把同一张卡的风险计数重复推进，导致后续误拒绝。
        if (!inboxRepository.claim(CONSUMER_NAME, command.sourceEventId(), Instant.now(clock))) {
            log.info("risk_feature_event_duplicate eventId={}", command.sourceEventId());
            return;
        }

        mapper.upsertDecision(command.cardId(), command.outcome().name(), command.decidedAt());
        log.info(
                "risk_feature_projected eventId={} cardId={} outcome={}",
                command.sourceEventId(),
                command.cardId(),
                command.outcome()
        );
    }
}
