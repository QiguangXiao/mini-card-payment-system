package com.minicard.risk.infrastructure.external;

import com.minicard.risk.application.ExternalRiskGateway;
import com.minicard.risk.domain.RiskAssessmentRequest;
import com.minicard.risk.domain.RiskDecision;
import com.minicard.risk.domain.RiskDeclineReason;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 外部风控的 infrastructure adapter。
 *
 * <p>关键词：外部风控, Feign, CircuitBreaker, external risk gateway,
 * fail-closed, 外部審査(がいぶしんさ)。</p>
 *
 * <p>RiskAssessmentService 依赖 ExternalRiskGateway port；这个 adapter 才知道
 * Feign DTO、HTTP endpoint 和 Resilience4j fallback。</p>
 */
@Component
@RequiredArgsConstructor
public class ExternalRiskGatewayAdapter implements ExternalRiskGateway {

    private final ExternalRiskClient externalRiskClient;

    @Override
    // @CircuitBreaker 依赖 Spring AOP proxy。fallback signature 必须包含原参数和 Throwable。
    // 如果没有断路器，外部风控慢/挂时会拖住授权请求，并把 DB lock 等待一起放大。
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
        // 外部风控不可用时 fail-closed，避免在无法判断风险时继续批准授权。
        return RiskDecision.decline(RiskDeclineReason.EXTERNAL_RISK_UNAVAILABLE, 100);
    }
}
