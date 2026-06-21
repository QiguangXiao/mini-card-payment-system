package com.minicard.infrastructure.cache;

import java.time.Duration;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Snapshot cache 配置。
 *
 * <p>配置按 cache name 拆开，是为了之后继续加入别的低风险 snapshot 时，只新增一个命名 cache，
 * 不需要复制 Caffeine/Redis 组合逻辑。</p>
 */
@ConfigurationProperties(prefix = "snapshot-cache")
public record SnapshotCacheProperties(
        boolean enabled,
        String keyPrefix,
        Map<String, CacheSpec> caches
) {

    private static final String DEFAULT_KEY_PREFIX = "mini-card";

    public SnapshotCacheProperties {
        if (keyPrefix == null || keyPrefix.isBlank()) {
            keyPrefix = DEFAULT_KEY_PREFIX;
        }
        caches = caches == null ? Map.of() : Map.copyOf(caches);
    }

    public CacheSpec cache(String cacheName) {
        return caches.getOrDefault(cacheName, CacheSpec.defaults());
    }

    public record CacheSpec(
            Duration localTtl,
            long maximumSize,
            Duration remoteTtl,
            Duration remoteTtlJitter
    ) {

        private static final Duration DEFAULT_LOCAL_TTL = Duration.ofSeconds(30);
        private static final Duration DEFAULT_REMOTE_TTL = Duration.ofMinutes(5);
        private static final long DEFAULT_MAXIMUM_SIZE = 1_000;

        public CacheSpec {
            if (localTtl == null || localTtl.isZero() || localTtl.isNegative()) {
                localTtl = DEFAULT_LOCAL_TTL;
            }
            if (maximumSize <= 0) {
                maximumSize = DEFAULT_MAXIMUM_SIZE;
            }
            if (remoteTtl == null || remoteTtl.isZero() || remoteTtl.isNegative()) {
                remoteTtl = DEFAULT_REMOTE_TTL;
            }
            if (remoteTtlJitter == null || remoteTtlJitter.isNegative()) {
                remoteTtlJitter = Duration.ZERO;
            }
        }

        public static CacheSpec defaults() {
            return new CacheSpec(
                    DEFAULT_LOCAL_TTL,
                    DEFAULT_MAXIMUM_SIZE,
                    DEFAULT_REMOTE_TTL,
                    Duration.ZERO
            );
        }
    }
}
