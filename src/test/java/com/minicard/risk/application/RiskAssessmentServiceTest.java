package com.minicard.risk.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.Map;
import java.util.Set;

import com.minicard.authorization.domain.Money;
import com.minicard.risk.domain.RiskAssessmentRequest;
import com.minicard.risk.domain.RiskDecision;
import com.minicard.risk.domain.RiskDeclineReason;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RiskAssessmentServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-14T00:00:00Z");

    @Test
    void localBlockedMerchantShortCircuitsExternalRisk() {
        RiskVelocityCounter velocityCounter = mock(RiskVelocityCounter.class);
        ExternalRiskGateway externalRiskGateway = mock(ExternalRiskGateway.class);
        RiskAssessmentService service = service(velocityCounter, externalRiskGateway);

        RiskDecision decision = service.assess(request("merchant-blocked", "100.00"));

        assertThat(decision.declineReason()).isEqualTo(RiskDeclineReason.BLOCKED_MERCHANT);
        verify(externalRiskGateway, never()).assess(request("merchant-blocked", "100.00"));
    }

    @Test
    void callsExternalRiskAfterLocalChecksPass() {
        RiskVelocityCounter velocityCounter = mock(RiskVelocityCounter.class);
        ExternalRiskGateway externalRiskGateway = mock(ExternalRiskGateway.class);
        RiskAssessmentRequest request = request("merchant-123", "100.00");
        when(velocityCounter.countRecentAuthorizations("card-123", NOW.minusSeconds(60)))
                .thenReturn(1);
        when(externalRiskGateway.assess(request)).thenReturn(RiskDecision.approve(10));

        RiskDecision decision = service(velocityCounter, externalRiskGateway).assess(request);

        assertThat(decision.approved()).isTrue();
        verify(externalRiskGateway).assess(request);
    }

    private RiskAssessmentService service(
            RiskVelocityCounter velocityCounter,
            ExternalRiskGateway externalRiskGateway
    ) {
        return new RiskAssessmentService(
                velocityCounter,
                externalRiskGateway,
                properties(),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private RiskProperties properties() {
        return new RiskProperties(
                new RiskProperties.Local(
                        60,
                        3,
                        Map.of("JPY", new BigDecimal("50000.00")),
                        Set.of("merchant-blocked")
                ),
                new RiskProperties.External(
                        "http://localhost:8080",
                        100,
                        0,
                        80
                )
        );
    }

    private RiskAssessmentRequest request(String merchantId, String amount) {
        return new RiskAssessmentRequest(
                "card-123",
                merchantId,
                "JP",
                "JP",
                new Money(new BigDecimal(amount), Currency.getInstance("JPY"))
        );
    }
}
