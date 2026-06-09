package com.minicard.risk.domain;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Currency;
import java.util.Map;
import java.util.Set;

import com.minicard.authorization.domain.Money;

public class LocalRiskPolicy {

    private final RiskVelocityRepository velocityRepository;
    private final Clock clock;
    private final Duration velocityWindow;
    private final int maxAuthorizationsPerWindow;
    private final Map<Currency, Money> highRiskAmountThresholds;
    private final Set<String> blockedMerchantIds;

    public LocalRiskPolicy(
            RiskVelocityRepository velocityRepository,
            Clock clock,
            Duration velocityWindow,
            int maxAuthorizationsPerWindow,
            Map<Currency, Money> highRiskAmountThresholds,
            Set<String> blockedMerchantIds
    ) {
        this.velocityRepository = velocityRepository;
        this.clock = clock;
        this.velocityWindow = velocityWindow;
        this.maxAuthorizationsPerWindow = maxAuthorizationsPerWindow;
        this.highRiskAmountThresholds = Map.copyOf(highRiskAmountThresholds);
        this.blockedMerchantIds = Set.copyOf(blockedMerchantIds);
    }

    public RiskDecision assess(RiskAssessmentRequest request) {
        // Merchant blacklist is a deterministic local rule. It is cheap, has no
        // network dependency, and should short-circuit before any external call.
        if (blockedMerchantIds.contains(request.merchantId())) {
            return RiskDecision.decline(RiskDeclineReason.BLOCKED_MERCHANT, 100);
        }

        // Velocity check demonstrates a classic card-risk algorithm: count
        // recent attempts in a sliding time window. In production this may read
        // from a low-latency store or stream aggregate; here it intentionally
        // uses the database so transaction history remains easy to inspect.
        Instant since = Instant.now(clock).minus(velocityWindow);
        int recentCount = velocityRepository.countRecentAuthorizations(request.cardId(), since);
        // The current authorization has already been inserted as PENDING for
        // audit/idempotency, so the count includes this request. Decline only
        // when the configured maximum is exceeded.
        if (recentCount > maxAuthorizationsPerWindow) {
            return RiskDecision.decline(RiskDeclineReason.VELOCITY_EXCEEDED, 90);
        }

        // Amount thresholds are configured per currency. Comparing "500" is
        // meaningless unless we know whether it means JPY, USD, or another
        // currency with different scale and risk semantics.
        Money highRiskAmountThreshold = highRiskAmountThresholds.get(
                request.amount().currency()
        );
        if (highRiskAmountThreshold != null
                && request.amount().isGreaterThan(highRiskAmountThreshold)) {
            return RiskDecision.decline(RiskDeclineReason.HIGH_RISK_AMOUNT, 85);
        }

        // Cross-border authorization is not always fraud, but it is a useful
        // interview-friendly signal. We model it as a local decline here to make
        // state transitions visible; a real issuer may instead add score and
        // ask the external risk engine for the final decision.
        if (request.isCrossBorder()) {
            return RiskDecision.decline(RiskDeclineReason.GEOLOCATION_MISMATCH, 75);
        }

        return RiskDecision.approve(20);
    }
}
