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
    // 这个 port 是防腐层入口：Feign response、HTTP status、timeout 都在 adapter 里转换成 RiskDecision。
    // 如果 service 直接依赖 Feign DTO，外部 API 字段会侵入业务判断。
    RiskDecision assess(RiskAssessmentRequest request);
}
