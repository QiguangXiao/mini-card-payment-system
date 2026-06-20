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
@EnableConfigurationProperties(AuthorizationPolicyProperties.class)
public class AuthorizationPolicyConfiguration {
}
