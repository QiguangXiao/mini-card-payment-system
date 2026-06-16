package com.minicard.risk.domain;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Currency;
import java.util.Map;
import java.util.Set;

import com.minicard.authorization.domain.Money;

/**
 * 本地风控策略，演示发卡行 authorization 前常见的 cheap risk checks。
 *
 * <p>这里故意使用可解释规则，而不是黑盒模型，方便学习系统设计时讲清楚
 * velocity、blocked merchant、high-risk amount、cross-border 等信号。</p>
 */
public class LocalRiskPolicy {

    private final RiskVelocityRepository velocityRepository;
    private final Clock clock;
    private final Duration velocityWindow;
    private final int maxAuthorizationsPerWindow;
    private final Map<Currency, Money> highRiskAmountThresholds;
    private final Set<String> blockedMerchantIds;

    public LocalRiskPolicy(
            RiskVelocityRepository velocityRepository,
            Clock clock,
            Duration velocityWindow,
            int maxAuthorizationsPerWindow,
            Map<Currency, Money> highRiskAmountThresholds,
            Set<String> blockedMerchantIds
    ) {
        this.velocityRepository = velocityRepository;
        this.clock = clock;
        this.velocityWindow = velocityWindow;
        this.maxAuthorizationsPerWindow = maxAuthorizationsPerWindow;
        this.highRiskAmountThresholds = Map.copyOf(highRiskAmountThresholds);
        this.blockedMerchantIds = Set.copyOf(blockedMerchantIds);
    }

    public RiskDecision assess(RiskAssessmentRequest request) {
        // Merchant blacklist 是确定性本地规则，没有网络依赖，应该在 external risk 前短路。
        if (blockedMerchantIds.contains(request.merchantId())) {
            return RiskDecision.decline(RiskDeclineReason.BLOCKED_MERCHANT, 100);
        }

        // Velocity check 是经典风控算法：统计 sliding time window 内的近期交易次数。
        // 生产里可用 Redis/stream aggregate；当前用 DB 让学习和排查更直观。
        Instant since = Instant.now(clock).minus(velocityWindow);
        int recentCount = velocityRepository.countRecentAuthorizations(request.cardId(), since);
        // 当前 authorization 已经作为 PENDING 插入，用于 audit/idempotency，
        // 所以 recentCount 包含本次请求；只有超过阈值才拒绝。
        if (recentCount > maxAuthorizationsPerWindow) {
            return RiskDecision.decline(RiskDeclineReason.VELOCITY_EXCEEDED, 90);
        }

        // Amount threshold 按 currency 配置。只比较“500”没有意义，必须知道是 JPY 还是 USD。
        Money highRiskAmountThreshold = highRiskAmountThresholds.get(
                request.amount().currency()
        );
        if (highRiskAmountThreshold != null
                && request.amount().isGreaterThan(highRiskAmountThreshold)) {
            return RiskDecision.decline(RiskDeclineReason.HIGH_RISK_AMOUNT, 85);
        }

        // Cross-border 不一定是欺诈，但它是面试中好解释的 risk signal。
        // 这里直接 decline 是为了让状态转换可见；真实 issuer 可能只是加分后交给模型决策。
        if (request.isCrossBorder()) {
            return RiskDecision.decline(RiskDeclineReason.GEOLOCATION_MISMATCH, 75);
        }

        return RiskDecision.approve(20);
    }
}
