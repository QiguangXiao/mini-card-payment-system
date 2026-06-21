package com.minicard.infrastructure.cache;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Snapshot cache infrastructure configuration。
 *
 * <p>关键词：缓存配置, 配置属性绑定, snapshot cache configuration,
 * configuration properties, インフラ設定(インフラせってい),
 * キャッシュ設定(キャッシュせってい)。</p>
 */
@Configuration
@EnableConfigurationProperties(SnapshotCacheProperties.class)
public class SnapshotCacheConfiguration {
}
