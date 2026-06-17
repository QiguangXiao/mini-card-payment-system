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
import com.minicard.risk.infrastructure.JdbcRiskVelocityRepository;
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
        JdbcRiskVelocityRepository velocityRepository = mock(JdbcRiskVelocityRepository.class);
        ExternalRiskService externalRiskService = mock(ExternalRiskService.class);
        RiskAssessmentService service = service(velocityRepository, externalRiskService);

        RiskDecision decision = service.assess(request("merchant-blocked", "100.00"));

        assertThat(decision.declineReason()).isEqualTo(RiskDeclineReason.BLOCKED_MERCHANT);
        verify(externalRiskService, never()).assess(request("merchant-blocked", "100.00"));
    }

    @Test
    void callsExternalRiskAfterLocalChecksPass() {
        JdbcRiskVelocityRepository velocityRepository = mock(JdbcRiskVelocityRepository.class);
        ExternalRiskService externalRiskService = mock(ExternalRiskService.class);
        RiskAssessmentRequest request = request("merchant-123", "100.00");
        when(velocityRepository.countRecentAuthorizations("card-123", NOW.minusSeconds(60)))
                .thenReturn(1);
        when(externalRiskService.assess(request)).thenReturn(RiskDecision.approve(10));

        RiskDecision decision = service(velocityRepository, externalRiskService).assess(request);

        assertThat(decision.approved()).isTrue();
        verify(externalRiskService).assess(request);
    }

    private RiskAssessmentService service(
            JdbcRiskVelocityRepository velocityRepository,
            ExternalRiskService externalRiskService
    ) {
        return new RiskAssessmentService(
                velocityRepository,
                externalRiskService,
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
