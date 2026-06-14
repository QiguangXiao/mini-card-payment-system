package com.minicard.risk.application.projection;

import java.time.Clock;
import java.time.Instant;

import com.minicard.messaging.inbox.ConsumerInboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Updates the Risk bounded context's eventually consistent feature projection.
 *
 * <p>The projection can later provide low-latency historical signals to risk
 * scoring. It must not be used for a hard real-time limit until its freshness
 * and consistency guarantees are explicitly acceptable.</p>
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
        // Inbox claim and projection update share one MySQL transaction. If the
        // projection fails, the claim rolls back and Kafka can safely redeliver.
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
