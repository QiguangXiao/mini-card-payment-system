package com.minicard.infrastructure.cache;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Read model cache infrastructure configuration。
 */
@Configuration
@EnableConfigurationProperties(ReadModelCacheProperties.class)
public class ReadModelCacheConfiguration {
}
