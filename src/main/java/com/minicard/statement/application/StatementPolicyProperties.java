package com.minicard.statement.application;

import java.math.BigDecimal;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Statement 金额策略配置。
 *
 * <p>关键词：最低还款额, 策略配置, 货币下限, minimum payment,
 * policy properties, currency floor, 最低支払額(さいていしはらいがく),
 * 請求ポリシー(せいきゅうポリシー)。</p>
 *
 * <p>当前用 rate + currency floor 计算 minimumPaymentAmount；真实信用卡还会有
 * 分期、手续费、循环利息等更复杂规则，这里先保持可解释。</p>
 */
@ConfigurationProperties(prefix = "statement.policy")
public record StatementPolicyProperties(
        /** 最低还款比例，例如 0.10 表示账单金额的 10%。 */
        BigDecimal minimumPaymentRate,
        /** 每个 currency 的最低还款 floor，例如 JPY 1000。 */
        Map<String, BigDecimal> minimumPaymentFloors
) {
}
