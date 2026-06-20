package com.minicard.risk.application;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 风控配置。
 *
 * <p>关键词：风控配置, 本地规则, 外部风控, risk properties,
 * local rules, external risk, リスク設定(リスクせってい),
 * 外部審査(がいぶしんさ)。</p>
 */
@ConfigurationProperties(prefix = "risk")
public record RiskProperties(
        /** 本地同步风控规则配置。 */
        Local local,
        /** 外部风控模拟服务配置。 */
        External external
) {

    /**
     * 本地风控规则配置。
     */
    public record Local(
            /** velocity 统计窗口秒数。 */
            long velocityWindowSeconds,
            /** 窗口内允许的最大授权次数。 */
            int maxAuthorizationsPerWindow,
            /** 各币种高风险金额阈值。 */
            Map<String, BigDecimal> highRiskAmountThresholds,
            /** 商户黑名单。 */
            Set<String> blockedMerchantIds
    ) {
    }

    /**
     * 外部风控模拟配置。
     */
    public record External(
            /** Feign 调用的 base URL。 */
            String baseUrl,
            /** 模拟延迟毫秒数。 */
            long simulatedLatencyMillis,
            /** 模拟失败率。 */
            int failureRatePercent,
            /** 高风险分数阈值。 */
            int highRiskScoreThreshold
    ) {
    }
}
