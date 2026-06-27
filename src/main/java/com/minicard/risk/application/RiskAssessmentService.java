package com.minicard.risk.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Currency;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import com.minicard.shared.domain.Money;
import com.minicard.risk.domain.RiskAssessmentRequest;
import com.minicard.risk.domain.RiskDecision;
import com.minicard.risk.domain.RiskDeclineReason;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

/**
 * 风控评估 use case，先跑本地规则，再调用模拟外部风控。
 *
 * <p>关键词：风控评估, 本地规则, 外部风控, risk assessment,
 * local checks, external risk, リスク評価(リスクひょうか),
 * 外部審査(がいぶしんさ)。</p>
 *
 * <p>interview重点：risk check 放在 account row lock 之前，避免慢风控调用扩大锁等待时间。</p>
 */
@Service
public class RiskAssessmentService {

    private final RiskVelocityCounter velocityCounter;
    private final ExternalRiskGateway externalRiskGateway;
    private final RiskProperties properties;
    private final Clock clock;
    private final MeterRegistry meterRegistry;
    private final Map<Currency, Money> highRiskAmountThresholds;

    public RiskAssessmentService(
            RiskVelocityCounter velocityCounter,
            ExternalRiskGateway externalRiskGateway,
            RiskProperties properties,
            Clock clock,
            MeterRegistry meterRegistry
    ) {
        this.velocityCounter = velocityCounter;
        this.externalRiskGateway = externalRiskGateway;
        this.properties = properties;
        this.clock = clock;
        this.meterRegistry = meterRegistry;
        this.highRiskAmountThresholds = properties.local().highRiskAmountThresholds()
                .entrySet()
                .stream()
                // toUnmodifiableMap 让配置转换结果不可变，避免运行中被测试或其他代码误改。
                // 如果保留可变 map，风控阈值会变成隐藏的全局状态，排查结果不稳定。
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

        return externalRiskGateway.assess(request);
    }

    private RiskDecision assessLocally(RiskAssessmentRequest request) {
        if (properties.local().blockedMerchantIds().contains(request.merchantId())) {
            return RiskDecision.decline(RiskDeclineReason.BLOCKED_MERCHANT, 100);
        }

        Instant since = Instant.now(clock).minus(
                Duration.ofSeconds(properties.local().velocityWindowSeconds())
        );
        VelocityCheckResult velocity = velocityCounter.countRecentAuthorizations(request.cardId(), since);
        if (velocity.degraded()) {
            meterRegistry.counter(
                    "risk.velocity.fallback.allow",
                    "source", velocity.source().name().toLowerCase(Locale.ROOT)
            ).increment();
            // Redis velocity 是辅助高频信号，所以 degraded 时按显式 fail-open policy 放行。
            // counterfactual：如果这里静默把 degraded 当作真正的 0 次，运维就看不到“正在失效放行”；
            // 如果全量回查 DB，Redis brownout 又会放大成 MySQL/Hikari brownout。
        }
        if (velocity.count() > properties.local().maxAuthorizationsPerWindow()) {
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
