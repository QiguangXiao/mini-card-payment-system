package com.minicard.risk.application;

import com.minicard.risk.domain.RiskAssessmentRequest;
import com.minicard.risk.domain.RiskDecision;

/**
 * 外部风控能力的 application port。
 *
 * <p>RiskAssessmentService 只依赖这个业务能力，不直接依赖 Feign/HTTP。
 * 当前 infrastructure adapter 会调用本项目内的模拟 external risk API。</p>
 */
public interface ExternalRiskGateway {

    /**
     * 调用外部风控并返回统一的 RiskDecision。
     */
    RiskDecision assess(RiskAssessmentRequest request);
}
