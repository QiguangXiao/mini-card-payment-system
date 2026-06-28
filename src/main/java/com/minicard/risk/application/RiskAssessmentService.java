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
    private final RiskFeatureReader riskFeatureReader;
    private final ExternalRiskGateway externalRiskGateway;
    private final RiskProperties properties;
    private final Clock clock;
    private final MeterRegistry meterRegistry;
    private final Map<Currency, Money> highRiskAmountThresholds;

    public RiskAssessmentService(
            RiskVelocityCounter velocityCounter,
            RiskFeatureReader riskFeatureReader,
            ExternalRiskGateway externalRiskGateway,
            RiskProperties properties,
            Clock clock,
            MeterRegistry meterRegistry
    ) {
        this.velocityCounter = velocityCounter;
        this.riskFeatureReader = riskFeatureReader;
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

        // 历史画像是 SOFT signal：先算出"这张卡是否要提高审查档位"，但它本身绝不单独拒绝。
        // 这是修掉旧实现"自我强化拒绝环"的关键 —— 详见 applyElevatedScrutiny 的 counterfactual。
        boolean elevatedScrutiny = hasElevatedHistoricalRisk(request);

        // 外部风控是最终判定依赖，带 timeout/bulkhead/circuit breaker（见 ExternalRiskGatewayAdapter）。
        RiskDecision external = externalRiskGateway.assess(request);

        return applyElevatedScrutiny(external, elevatedScrutiny);
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

        // 历史画像不再在这里硬拒绝；它被降级成 assess() 末尾与外部结果组合的 soft signal。
        return RiskDecision.approve(20);
    }

    /**
     * 历史画像只产出一个 boolean soft signal：是否对这张卡提高审查档位。
     *
     * <p>它读取 Kafka consumer 异步维护的 {@code card_risk_features}（eventually consistent
     * long-window profile），判断"样本足够 且 历史拒绝率超过阈值"。区别于 Redis 实时短窗口
     * velocity，这是慢变的历史信号，所以只用来收紧判定，绝不直接拍板拒绝。</p>
     *
     * <p>代价：每笔通过 cheap rules 的授权多一次 card_id 主键 read；它发生在 authorization
     * transaction 内，但只是本地 MySQL 命中、用已持有的连接，远比外部网络调用便宜。</p>
     */
    private boolean hasElevatedHistoricalRisk(RiskAssessmentRequest request) {
        boolean elevated = riskFeatureReader.findByCardId(request.cardId())
                .filter(feature -> feature.hasEnoughSample(
                        properties.local().minHistoricalAuthorizations()
                ))
                .filter(feature -> feature.declineRateAtLeast(
                        properties.local().maxHistoricalDeclineRate()
                ))
                .isPresent();
        if (elevated) {
            // 让"有多少卡被历史画像提高审查档位"可观测；突增往往意味着上游攻击或投影异常。
            meterRegistry.counter("risk.historical.elevated").increment();
        }
        return elevated;
    }

    /**
     * 把历史画像 soft signal 和外部风控结果组合成最终决定。
     *
     * <p>规则：外部已拒 → 直接采用；外部通过但本卡处于 elevated scrutiny 且外部分数偏高
     * （≥ {@code elevatedScrutinyMaxApprovedScore}）→ 收紧为拒绝；其余放行。</p>
     *
     * <p>counterfactual（这是整次修补的核心）：旧实现让历史画像在 external 之前<strong>单独硬拒绝</strong>，
     * 于是 1) 拒绝事件回灌 projection 抬高 decline rate；2) 计数终身累计不衰减；再加上数学上
     * {@code (d+1)/(t+1) ≥ d/t}，一张卡一旦越线就<strong>每笔必拒、approved_count 再也无法增长、
     * 永久 brick</strong>。改成 soft signal 后：历史再差，只要本次<strong>外部判定足够干净</strong>
     * 仍会放行，approved_count 得以增长、decline rate 自然回落，卡能自愈。配合 listener 不再统计
     * HISTORICAL_RISK_PROFILE 与非风险拒绝，自我强化环被彻底断开。</p>
     */
    private RiskDecision applyElevatedScrutiny(RiskDecision external, boolean elevatedScrutiny) {
        if (!external.approved()) {
            return external;
        }
        if (elevatedScrutiny
                && external.score() >= properties.local().elevatedScrutinyMaxApprovedScore()) {
            meterRegistry.counter("risk.historical.elevated.declined").increment();
            // 用 external.score() 作为拒绝分数，如实反映"外部本就给到偏高风险分"这一组合事实。
            return RiskDecision.decline(RiskDeclineReason.HISTORICAL_RISK_PROFILE, external.score());
        }
        return external;
    }

}
