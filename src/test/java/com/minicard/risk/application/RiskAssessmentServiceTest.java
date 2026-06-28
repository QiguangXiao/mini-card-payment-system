package com.minicard.risk.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.minicard.shared.domain.Money;
import com.minicard.risk.domain.RiskAssessmentRequest;
import com.minicard.risk.domain.RiskDecision;
import com.minicard.risk.domain.RiskDeclineReason;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
        RiskFeatureReader riskFeatureReader = mock(RiskFeatureReader.class);
        ExternalRiskGateway externalRiskGateway = mock(ExternalRiskGateway.class);
        RiskAssessmentService service = service(velocityCounter, riskFeatureReader, externalRiskGateway);

        RiskDecision decision = service.assess(request("merchant-blocked", "100.00"));

        assertThat(decision.declineReason()).isEqualTo(RiskDeclineReason.BLOCKED_MERCHANT);
        verify(externalRiskGateway, never()).assess(request("merchant-blocked", "100.00"));
    }

    @Test
    void callsExternalRiskAfterLocalChecksPass() {
        RiskVelocityCounter velocityCounter = mock(RiskVelocityCounter.class);
        RiskFeatureReader riskFeatureReader = mock(RiskFeatureReader.class);
        ExternalRiskGateway externalRiskGateway = mock(ExternalRiskGateway.class);
        RiskAssessmentRequest request = request("merchant-123", "100.00");
        when(velocityCounter.countRecentAuthorizations("card-123", NOW.minusSeconds(60)))
                .thenReturn(VelocityCheckResult.available(1, VelocitySource.REDIS));
        when(riskFeatureReader.findByCardId("card-123")).thenReturn(Optional.empty());
        when(externalRiskGateway.assess(request)).thenReturn(RiskDecision.approve(10));

        RiskDecision decision = service(velocityCounter, riskFeatureReader, externalRiskGateway)
                .assess(request);

        assertThat(decision.approved()).isTrue();
        verify(externalRiskGateway).assess(request);
    }

    @Test
    void velocityDegradedFailsOpenAndRecordsFallbackMetric() {
        RiskVelocityCounter velocityCounter = mock(RiskVelocityCounter.class);
        RiskFeatureReader riskFeatureReader = mock(RiskFeatureReader.class);
        ExternalRiskGateway externalRiskGateway = mock(ExternalRiskGateway.class);
        RiskAssessmentRequest request = request("merchant-123", "100.00");
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        when(velocityCounter.countRecentAuthorizations("card-123", NOW.minusSeconds(60)))
                .thenReturn(VelocityCheckResult.degradedAllow(VelocitySource.REDIS));
        when(riskFeatureReader.findByCardId("card-123")).thenReturn(Optional.empty());
        when(externalRiskGateway.assess(request)).thenReturn(RiskDecision.approve(10));

        RiskDecision decision = service(
                velocityCounter,
                riskFeatureReader,
                externalRiskGateway,
                meterRegistry
        ).assess(request);

        assertThat(decision.approved()).isTrue();
        assertThat(meterRegistry.counter("risk.velocity.fallback.allow", "source", "redis").count())
                .isEqualTo(1.0);
        verify(externalRiskGateway).assess(request);
    }

    @Test
    void elevatedHistoricalProfileTightensBorderlineExternalApproval() {
        RiskVelocityCounter velocityCounter = mock(RiskVelocityCounter.class);
        RiskFeatureReader riskFeatureReader = mock(RiskFeatureReader.class);
        ExternalRiskGateway externalRiskGateway = mock(ExternalRiskGateway.class);
        RiskAssessmentRequest request = request("merchant-123", "100.00");
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        when(velocityCounter.countRecentAuthorizations("card-123", NOW.minusSeconds(60)))
                .thenReturn(VelocityCheckResult.available(1, VelocitySource.REDIS));
        // 历史拒绝率 9/10 = 0.9 ≥ 0.80 且样本 10 ≥ 5 → elevated scrutiny。
        when(riskFeatureReader.findByCardId("card-123"))
                .thenReturn(Optional.of(new CardRiskFeature(
                        "card-123", 10, 1, 9, NOW.minusSeconds(1)
                )));
        // 外部"通过但分数偏高"(70 ≥ 60)：elevated 下被收紧为拒绝。
        // 关键断言：外部仍被调用——历史画像是组合判定的一方，而不是在 external 之前单独拍板。
        when(externalRiskGateway.assess(request)).thenReturn(RiskDecision.approve(70));

        RiskDecision decision = service(
                velocityCounter, riskFeatureReader, externalRiskGateway, meterRegistry
        ).assess(request);

        assertThat(decision.approved()).isFalse();
        assertThat(decision.declineReason()).isEqualTo(RiskDeclineReason.HISTORICAL_RISK_PROFILE);
        verify(externalRiskGateway).assess(request);
        assertThat(meterRegistry.counter("risk.historical.elevated.declined").count())
                .isEqualTo(1.0);
    }

    @Test
    void elevatedHistoricalProfileStillApprovesWhenExternalIsClean() {
        // 修复"永久 brick"的核心保证：历史再差，只要本次外部判定足够干净仍放行，卡能自愈。
        RiskVelocityCounter velocityCounter = mock(RiskVelocityCounter.class);
        RiskFeatureReader riskFeatureReader = mock(RiskFeatureReader.class);
        ExternalRiskGateway externalRiskGateway = mock(ExternalRiskGateway.class);
        RiskAssessmentRequest request = request("merchant-123", "100.00");
        when(velocityCounter.countRecentAuthorizations("card-123", NOW.minusSeconds(60)))
                .thenReturn(VelocityCheckResult.available(1, VelocitySource.REDIS));
        when(riskFeatureReader.findByCardId("card-123"))
                .thenReturn(Optional.of(new CardRiskFeature(
                        "card-123", 10, 1, 9, NOW.minusSeconds(1)
                )));
        // 外部干净通过(score 10 < 60)：即使历史很差也放行。
        when(externalRiskGateway.assess(request)).thenReturn(RiskDecision.approve(10));

        RiskDecision decision = service(velocityCounter, riskFeatureReader, externalRiskGateway)
                .assess(request);

        assertThat(decision.approved()).isTrue();
        verify(externalRiskGateway).assess(request);
    }

    @Test
    void historicalRiskProfileIgnoresSmallSamples() {
        RiskVelocityCounter velocityCounter = mock(RiskVelocityCounter.class);
        RiskFeatureReader riskFeatureReader = mock(RiskFeatureReader.class);
        ExternalRiskGateway externalRiskGateway = mock(ExternalRiskGateway.class);
        RiskAssessmentRequest request = request("merchant-123", "100.00");
        when(velocityCounter.countRecentAuthorizations("card-123", NOW.minusSeconds(60)))
                .thenReturn(VelocityCheckResult.available(1, VelocitySource.REDIS));
        when(riskFeatureReader.findByCardId("card-123"))
                .thenReturn(Optional.of(new CardRiskFeature(
                        "card-123",
                        4,
                        0,
                        4,
                        NOW.minusSeconds(1)
                )));
        when(externalRiskGateway.assess(request)).thenReturn(RiskDecision.approve(10));

        RiskDecision decision = service(velocityCounter, riskFeatureReader, externalRiskGateway)
                .assess(request);

        assertThat(decision.approved()).isTrue();
        verify(externalRiskGateway).assess(request);
    }

    private RiskAssessmentService service(
            RiskVelocityCounter velocityCounter,
            RiskFeatureReader riskFeatureReader,
            ExternalRiskGateway externalRiskGateway
    ) {
        return service(
                velocityCounter,
                riskFeatureReader,
                externalRiskGateway,
                new SimpleMeterRegistry()
        );
    }

    private RiskAssessmentService service(
            RiskVelocityCounter velocityCounter,
            RiskFeatureReader riskFeatureReader,
            ExternalRiskGateway externalRiskGateway,
            SimpleMeterRegistry meterRegistry
    ) {
        return new RiskAssessmentService(
                velocityCounter,
                riskFeatureReader,
                externalRiskGateway,
                properties(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                meterRegistry
        );
    }

    private RiskProperties properties() {
        return new RiskProperties(
                new RiskProperties.Local(
                        60,
                        3,
                        5,
                        new BigDecimal("0.80"),
                        60,
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
