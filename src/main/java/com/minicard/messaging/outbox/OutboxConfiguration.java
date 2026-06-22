package com.minicard.messaging.outbox;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Outbox 配置绑定入口。
 *
 * <p>关键词：Outbox 配置, 可靠发布, Spring 配置, outbox configuration,
 * reliable publication, 設定バインド(せっていバインド),
 * 確実発行(かくじつはっこう)。</p>
 */
@Configuration
// @EnableConfigurationProperties 把 OutboxProperties 注册成 Spring bean。
// 如果只在 record 上写 @ConfigurationProperties 而没有启用绑定，constructor injection 会启动失败。
@EnableConfigurationProperties(OutboxProperties.class)
public class OutboxConfiguration {
}
