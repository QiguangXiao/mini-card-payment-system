package com.minicard.authorization.application;

import java.math.BigDecimal;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Authorization 产品策略配置。
 *
 * <p>关键词：授权策略, 单笔限额, 配置绑定, authorization policy,
 * single transaction limit, configuration properties, オーソリ設定(オーソリせってい),
 * 利用限度額(りようげんどがく)。</p>
 */
@ConfigurationProperties(prefix = "authorization.policy")
public record AuthorizationPolicyProperties(
        /** 按 currency 配置单笔交易上限。 */
        Map<String, BigDecimal> singleTransactionLimits
) {
}
