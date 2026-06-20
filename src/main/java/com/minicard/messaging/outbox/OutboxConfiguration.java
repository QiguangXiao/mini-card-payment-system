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
@EnableConfigurationProperties(OutboxProperties.class)
public class OutboxConfiguration {
}
