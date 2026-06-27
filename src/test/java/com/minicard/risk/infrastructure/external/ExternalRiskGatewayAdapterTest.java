package com.minicard.risk.infrastructure.external;

import java.math.BigDecimal;
import java.util.Currency;

import com.minicard.risk.domain.RiskAssessmentRequest;
import com.minicard.risk.domain.RiskDecision;
import com.minicard.risk.domain.RiskDeclineReason;
import com.minicard.shared.domain.Money;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExternalRiskGatewayAdapterTest {

    @Test
    void recordsLatencyMetricForSuccessfulExternalRiskCall() {
        ExternalRiskClient client = mock(ExternalRiskClient.class);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        ExternalRiskGatewayAdapter adapter = new ExternalRiskGatewayAdapter(client, meterRegistry);
        when(client.assess(any())).thenReturn(new ExternalRiskClient.ExternalRiskResponse(true, 10));

        RiskDecision decision = adapter.assess(request());

        assertThat(decision.approved()).isTrue();
        assertThat(meterRegistry.find("risk.external.latency")
                .tag("outcome", "approved")
                .timer()
                .count()).isEqualTo(1);
    }

    @Test
    void fallbackFailsClosedAndRecordsBulkheadRejectionMetric() {
        ExternalRiskClient client = mock(ExternalRiskClient.class);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        ExternalRiskGatewayAdapter adapter = new ExternalRiskGatewayAdapter(client, meterRegistry);

        RiskDecision decision = adapter.fallback(
                request(),
                BulkheadFullException.createBulkheadFullException(
                        io.github.resilience4j.bulkhead.Bulkhead.ofDefaults("externalRisk")
                )
        );

        assertThat(decision.approved()).isFalse();
        assertThat(decision.declineReason()).isEqualTo(RiskDeclineReason.EXTERNAL_RISK_UNAVAILABLE);
        assertThat(meterRegistry.counter("risk.external.fallback", "reason", "bulkhead_full").count())
                .isEqualTo(1);
        assertThat(meterRegistry.counter("risk.external.bulkhead.rejected").count()).isEqualTo(1);
    }

    private RiskAssessmentRequest request() {
        return new RiskAssessmentRequest(
                "card-123",
                "merchant-123",
                "JP",
                "JP",
                new Money(new BigDecimal("100.00"), Currency.getInstance("JPY"))
        );
    }
}
