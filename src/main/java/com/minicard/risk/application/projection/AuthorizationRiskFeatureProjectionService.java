package com.minicard.risk.application.projection;

import java.time.Clock;
import java.time.Instant;

import com.minicard.messaging.inbox.ConsumerInboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 更新 Risk bounded context 的 eventually consistent feature projection。
 *
 * <p>projection 未来可给 risk scoring 提供低延迟历史信号。
 * 但在明确 freshness/consistency 保证前，不能把它当强实时限流依据。</p>
 */
@Service
public class AuthorizationRiskFeatureProjectionService {

    public static final String CONSUMER_NAME = "risk-feature-v1";

    private static final Logger log =
            LoggerFactory.getLogger(AuthorizationRiskFeatureProjectionService.class);

    private final ConsumerInboxRepository inboxRepository;
    private final CardRiskFeatureProjectionRepository projectionRepository;
    private final Clock clock;

    public AuthorizationRiskFeatureProjectionService(
            ConsumerInboxRepository inboxRepository,
            CardRiskFeatureProjectionRepository projectionRepository,
            Clock clock
    ) {
        this.inboxRepository = inboxRepository;
        this.projectionRepository = projectionRepository;
        this.clock = clock;
    }

    @Transactional
    public void project(RecordAuthorizationDecisionCommand decision) {
        // Inbox claim 和 projection update 共用一个 MySQL transaction。
        // projection 失败时 claim 会 rollback，Kafka 可以安全 redeliver。
        if (!inboxRepository.claim(CONSUMER_NAME, decision.eventId(), Instant.now(clock))) {
            log.info("risk_feature_event_duplicate eventId={}", decision.eventId());
            return;
        }

        projectionRepository.applyDecision(decision);
        log.info(
                "risk_feature_projected eventId={} cardId={} status={}",
                decision.eventId(),
                decision.cardId(),
                decision.status()
        );
    }
}
