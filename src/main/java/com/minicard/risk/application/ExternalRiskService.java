package com.minicard.risk.application;

import com.minicard.risk.domain.RiskAssessmentRequest;
import com.minicard.risk.domain.RiskDecision;
import com.minicard.risk.domain.RiskDeclineReason;
import com.minicard.risk.infrastructure.external.ExternalRiskClient;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ExternalRiskService {

    private final ExternalRiskClient externalRiskClient;

    @CircuitBreaker(name = "externalRisk", fallbackMethod = "fallback")
    public RiskDecision assess(RiskAssessmentRequest request) {
        // Feign client 调用模拟第三方 risk API；CircuitBreaker fallback 采用 fail-closed。
        ExternalRiskClient.ExternalRiskResponse response = externalRiskClient.assess(
                new ExternalRiskClient.ExternalRiskRequest(
                        request.cardId(),
                        request.merchantId(),
                        request.merchantCountry(),
                        request.cardholderCountry(),
                        request.amount().amount(),
                        request.amount().currency().getCurrencyCode()
                )
        );
        if (!response.approved()) {
            return RiskDecision.decline(RiskDeclineReason.EXTERNAL_RISK_DECLINED, response.score());
        }
        return RiskDecision.approve(response.score());
    }

    @SuppressWarnings("unused")
    public RiskDecision fallback(RiskAssessmentRequest request, Throwable throwable) {
        return RiskDecision.decline(RiskDeclineReason.EXTERNAL_RISK_UNAVAILABLE, 100);
    }
}
