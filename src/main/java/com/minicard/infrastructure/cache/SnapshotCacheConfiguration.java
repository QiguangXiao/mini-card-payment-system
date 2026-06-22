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
// 这个配置类只负责启用 typed properties；具体 cache bean 由各业务模块声明自己的 cache name。
// 如果所有模块直接共享一个无名 cache，Redis key/version 管理会很快变混乱。
@EnableConfigurationProperties(SnapshotCacheProperties.class)
public class SnapshotCacheConfiguration {
}
