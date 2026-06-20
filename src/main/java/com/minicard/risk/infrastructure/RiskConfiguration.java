package com.minicard.risk.infrastructure;

import com.minicard.risk.application.RiskProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Risk 配置绑定入口。
 *
 * <p>关键词：风控配置绑定, Spring 配置, risk configuration,
 * configuration properties, リスク設定(リスクせってい),
 * 設定バインド(せっていバインド)。</p>
 */
@Configuration
@EnableConfigurationProperties(RiskProperties.class)
public class RiskConfiguration {
}
