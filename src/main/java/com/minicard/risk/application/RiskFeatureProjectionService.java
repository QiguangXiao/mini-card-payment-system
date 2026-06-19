package com.minicard.risk.application;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

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
    public void project(UUID eventId, String cardId, String status, Instant decidedAt) {
        if (!inboxRepository.claim(CONSUMER_NAME, eventId, Instant.now(clock))) {
            log.info("risk_feature_event_duplicate eventId={}", eventId);
            return;
        }

        mapper.upsertDecision(cardId, status, decidedAt);
        log.info("risk_feature_projected eventId={} cardId={} status={}", eventId, cardId, status);
    }
}
