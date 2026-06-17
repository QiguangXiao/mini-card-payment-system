package com.minicard.risk.infrastructure.external;

import java.math.BigDecimal;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "external-risk", url = "${risk.external.base-url}")
public interface ExternalRiskClient {

    @PostMapping("/external-risk/assess")
    ExternalRiskResponse assess(@RequestBody ExternalRiskRequest request);

    record ExternalRiskRequest(
            String cardId,
            String merchantId,
            String merchantCountry,
            String cardholderCountry,
            BigDecimal amount,
            String currency
    ) {
    }

    record ExternalRiskResponse(
            boolean approved,
            int score
    ) {
    }
}
