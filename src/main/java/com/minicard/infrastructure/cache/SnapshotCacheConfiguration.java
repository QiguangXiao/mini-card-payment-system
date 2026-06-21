package com.minicard.infrastructure.cache;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Snapshot cache infrastructure configuration。
 */
@Configuration
@EnableConfigurationProperties(SnapshotCacheProperties.class)
public class SnapshotCacheConfiguration {
}
