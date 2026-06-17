package com.minicard.risk.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Currency;
import java.util.Map;
import java.util.stream.Collectors;

import com.minicard.authorization.domain.Money;
import com.minicard.risk.domain.RiskAssessmentRequest;
import com.minicard.risk.domain.RiskDecision;
import com.minicard.risk.domain.RiskDeclineReason;
import com.minicard.risk.infrastructure.JdbcRiskVelocityRepository;
import org.springframework.stereotype.Service;

/**
 * 风控评估 use case，先跑本地规则，再调用模拟外部风控。
 *
 * <p>面试重点：risk check 放在 account row lock 之前，避免慢风控调用扩大锁等待时间。</p>
 */
@Service
public class RiskAssessmentService {

    private final JdbcRiskVelocityRepository velocityRepository;
    private final ExternalRiskService externalRiskService;
    private final RiskProperties properties;
    private final Clock clock;
    private final Map<Currency, Money> highRiskAmountThresholds;

    public RiskAssessmentService(
            JdbcRiskVelocityRepository velocityRepository,
            ExternalRiskService externalRiskService,
            RiskProperties properties,
            Clock clock
    ) {
        this.velocityRepository = velocityRepository;
        this.externalRiskService = externalRiskService;
        this.properties = properties;
        this.clock = clock;
        this.highRiskAmountThresholds = properties.local().highRiskAmountThresholds()
                .entrySet()
                .stream()
                .collect(Collectors.toUnmodifiableMap(
                        entry -> Currency.getInstance(entry.getKey()),
                        entry -> new Money(entry.getValue(), Currency.getInstance(entry.getKey()))
                ));
    }

    public RiskDecision assess(RiskAssessmentRequest request) {
        // Local checks 是确定性且便宜的规则，先执行可以让明显高风险请求不进入外部调用。
        RiskDecision localDecision = assessLocally(request);
        if (!localDecision.approved()) {
            return localDecision;
        }

        return externalRiskService.assess(request);
    }

    private RiskDecision assessLocally(RiskAssessmentRequest request) {
        if (properties.local().blockedMerchantIds().contains(request.merchantId())) {
            return RiskDecision.decline(RiskDeclineReason.BLOCKED_MERCHANT, 100);
        }

        Instant since = Instant.now(clock).minus(
                Duration.ofSeconds(properties.local().velocityWindowSeconds())
        );
        int recentCount = velocityRepository.countRecentAuthorizations(request.cardId(), since);
        if (recentCount > properties.local().maxAuthorizationsPerWindow()) {
            return RiskDecision.decline(RiskDeclineReason.VELOCITY_EXCEEDED, 90);
        }

        Money highRiskAmountThreshold = highRiskAmountThresholds.get(request.amount().currency());
        if (highRiskAmountThreshold != null
                && request.amount().isGreaterThan(highRiskAmountThreshold)) {
            return RiskDecision.decline(RiskDeclineReason.HIGH_RISK_AMOUNT, 85);
        }

        if (request.isCrossBorder()) {
            return RiskDecision.decline(RiskDeclineReason.GEOLOCATION_MISMATCH, 75);
        }

        return RiskDecision.approve(20);
    }

}
