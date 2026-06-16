package com.minicard.risk.application;

import com.minicard.risk.domain.LocalRiskPolicy;
import com.minicard.risk.domain.RiskAssessmentRequest;
import com.minicard.risk.domain.RiskDecision;
import com.minicard.risk.infrastructure.external.ExternalRiskGateway;
import org.springframework.stereotype.Service;

/**
 * 风控评估 use case，先跑本地规则，再调用模拟外部风控。
 *
 * <p>面试重点：risk check 放在 account row lock 之前，避免慢风控调用扩大锁等待时间。</p>
 */
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
        // Local checks 是确定性且便宜的规则，先执行可以让明显高风险请求不进入外部调用。
        RiskDecision localDecision = localRiskPolicy.assess(request);
        if (!localDecision.approved()) {
            return localDecision;
        }

        // External risk 由 timeout、circuit breaker、fallback 保护。
        // 生产环境这里通常会调用内部/第三方 risk engine。
        return externalRiskGateway.assess(request).join();
    }
}
