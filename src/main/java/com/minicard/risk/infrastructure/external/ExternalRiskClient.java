package com.minicard.risk.infrastructure.external;

import java.math.BigDecimal;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 外部风控 Feign client。
 *
 * <p>关键词：外部风控, Feign, HTTP 调用, external risk,
 * remote risk service, declarative client, 外部審査(がいぶしんさ),
 * HTTP連携(HTTPれんけい)。</p>
 *
 * <p>@FeignClient 是 Spring Cloud OpenFeign 的高级语法，运行时生成 HTTP client 代理。</p>
 */
@FeignClient(name = "external-risk", url = "${risk.external.base-url}")
public interface ExternalRiskClient {

    /**
     * 调用外部风控评估接口。
     */
    @PostMapping("/external-risk/assess")
    ExternalRiskResponse assess(@RequestBody ExternalRiskRequest request);

    /**
     * 外部风控请求 DTO。
     */
    record ExternalRiskRequest(
            /** card id。 */
            String cardId,
            /** merchant id。 */
            String merchantId,
            /** 商户国家。 */
            String merchantCountry,
            /** 持卡人国家。 */
            String cardholderCountry,
            /** 授权金额。 */
            BigDecimal amount,
            /** 币种。 */
            String currency
    ) {
    }

    /**
     * 外部风控响应 DTO。
     */
    record ExternalRiskResponse(
            /** 外部风控是否批准。 */
            boolean approved,
            /** 外部风控评分。 */
            int score
    ) {
    }
}
