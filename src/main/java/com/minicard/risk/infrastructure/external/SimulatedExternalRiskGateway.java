package com.minicard.risk.infrastructure.external;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

import com.minicard.risk.domain.RiskAssessmentRequest;
import com.minicard.risk.domain.RiskDecision;
import com.minicard.risk.domain.RiskDeclineReason;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.timelimiter.annotation.TimeLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class SimulatedExternalRiskGateway implements ExternalRiskGateway {

    private static final Logger log = LoggerFactory.getLogger(SimulatedExternalRiskGateway.class);

    private final ExternalRiskProperties properties;

    public SimulatedExternalRiskGateway(ExternalRiskProperties properties) {
        this.properties = properties;
    }

    @Override
    @TimeLimiter(name = "externalRisk", fallbackMethod = "fallback")
    @CircuitBreaker(name = "externalRisk", fallbackMethod = "fallback")
    public CompletableFuture<RiskDecision> assess(RiskAssessmentRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            simulateLatency();
            maybeFail();

            int score = score(request);
            if (score >= properties.highRiskScoreThreshold()) {
                return RiskDecision.decline(RiskDeclineReason.EXTERNAL_RISK_DECLINED, score);
            }
            return RiskDecision.approve(score);
        });
    }

    @SuppressWarnings("unused")
    CompletableFuture<RiskDecision> fallback(RiskAssessmentRequest request, Throwable throwable) {
        // This is intentionally fail-closed for learning purposes. Some real
        // issuers may fail-open for low-value transactions, but that decision
        // must be explicit and audited.
        log.warn(
                "external_risk_fallback cardId={} merchantId={} reason={}",
                request.cardId(),
                request.merchantId(),
                throwable.getClass().getSimpleName()
        );
        return CompletableFuture.completedFuture(
                RiskDecision.decline(RiskDeclineReason.EXTERNAL_RISK_UNAVAILABLE, 100)
        );
    }

    private void simulateLatency() {
        try {
            Thread.sleep(properties.simulatedLatencyMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("external risk call was interrupted", exception);
        }
    }

    private void maybeFail() {
        if (properties.failureRatePercent() <= 0) {
            return;
        }
        int sample = ThreadLocalRandom.current().nextInt(100);
        if (sample < properties.failureRatePercent()) {
            throw new IllegalStateException("simulated external risk failure");
        }
    }

    private int score(RiskAssessmentRequest request) {
        int score = 10;
        if (request.isCrossBorder()) {
            score += 35;
        }
        // A tiny deterministic scoring model is easier to explain than a random
        // mock: country mismatch, unusually large amount, and unfamiliar-looking
        // merchant each push the score toward decline.
        if (request.amount().amount().intValue() >= 30000) {
            score += 25;
        }
        if (request.merchantId().contains("new")) {
            score += 15;
        }
        return Math.min(score, 100);
    }
}
