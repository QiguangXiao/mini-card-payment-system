package com.minicard.infrastructure.cache;

import java.util.function.Function;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Caffeine L1 + Redis L2 read-through cache factory。
 *
 * <p>业务模块只声明 cache name、value type 和 key encoder；TTL、key prefix、
 * Redis fallback 等生产细节集中在 infrastructure，避免每个模块复制一套缓存代码。</p>
 */
@Component
@RequiredArgsConstructor
public class ReadModelCacheFactory {

    private final ReadModelCacheProperties properties;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public <K, V> ReadModelCache<K, V> create(
            String cacheName,
            Class<V> valueType,
            Function<K, String> keyEncoder
    ) {
        if (!properties.enabled()) {
            return new NoOpReadModelCache<>();
        }

        ReadModelCacheProperties.CacheSpec spec = properties.cache(cacheName);
        Cache<K, V> localCache = Caffeine.newBuilder()
                .maximumSize(spec.maximumSize())
                .expireAfterWrite(spec.localTtl())
                .recordStats()
                .build();
        return new TwoLevelReadModelCache<>(
                properties.keyPrefix(),
                cacheName,
                spec,
                localCache,
                redisTemplate,
                objectMapper,
                valueType,
                keyEncoder
        );
    }
}
