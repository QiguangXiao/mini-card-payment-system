package com.minicard.risk.infrastructure.external;

import java.util.concurrent.CompletableFuture;

import com.minicard.risk.domain.RiskAssessmentRequest;
import com.minicard.risk.domain.RiskDecision;

public interface ExternalRiskGateway {

    CompletableFuture<RiskDecision> assess(RiskAssessmentRequest request);
}
