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
// 启用 RiskProperties 绑定，让阈值和开关在启动期进入 Spring bean。
// 如果 service 直接读取环境变量，测试很难构造不同风险策略。
@EnableConfigurationProperties(RiskProperties.class)
public class RiskConfiguration {
}
