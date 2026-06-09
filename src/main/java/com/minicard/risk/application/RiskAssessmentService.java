package com.minicard.risk.application;

import com.minicard.risk.domain.LocalRiskPolicy;
import com.minicard.risk.domain.RiskAssessmentRequest;
import com.minicard.risk.domain.RiskDecision;
import com.minicard.risk.infrastructure.external.ExternalRiskGateway;
import org.springframework.stereotype.Service;

@Service
public class RiskAssessmentService {

    private final LocalRiskPolicy localRiskPolicy;
    private final ExternalRiskGateway externalRiskGateway;

    public RiskAssessmentService(
            LocalRiskPolicy localRiskPolicy,
            ExternalRiskGateway externalRiskGateway
    ) {
        this.localRiskPolicy = localRiskPolicy;
        this.externalRiskGateway = externalRiskGateway;
    }

    public RiskDecision assess(RiskAssessmentRequest request) {
        // Local checks are deterministic and cheap, so they run before the
        // simulated third-party call. This avoids external latency for obviously
        // risky requests.
        RiskDecision localDecision = localRiskPolicy.assess(request);
        if (!localDecision.approved()) {
            return localDecision;
        }

        // External risk is protected by timeout, circuit breaker, and fallback.
        // In production this would call an internal/third-party risk engine.
        return externalRiskGateway.assess(request).join();
    }
}
