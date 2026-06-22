package com.minicard.authorization.infrastructure;

import com.minicard.authorization.application.AuthorizationPolicyProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Authorization 配置绑定入口。
 *
 * <p>关键词：配置绑定, 授权策略, Spring 配置, configuration properties,
 * authorization policy, 設定バインド(せっていバインド),
 * オーソリ設定(オーソリせってい)。</p>
 */
@Configuration
// @EnableConfigurationProperties 会注册 AuthorizationPolicyProperties bean。
// 如果只在 record 上写 @ConfigurationProperties 但没有启用绑定，AuthorizationService 启动时会找不到配置 bean。
@EnableConfigurationProperties(AuthorizationPolicyProperties.class)
public class AuthorizationPolicyConfiguration {
}
