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
 * <p>关键词：两级缓存工厂, 本地缓存, 分布式缓存, Caffeine L1,
 * Redis L2, read-through cache, キャッシュファクトリ,
 * 分散キャッシュ(ぶんさんキャッシュ)。</p>
 *
 * <p>业务模块只声明 cache name、value type 和 key encoder；TTL、key prefix、
 * Redis fallback 等生产细节集中在 infrastructure，避免每个模块复制一套缓存代码。</p>
 */
@Component
@RequiredArgsConstructor
public class SnapshotCacheFactory {

    private final SnapshotCacheProperties properties;
    // StringRedisTemplate 表达“Redis value 是 String JSON”，避免默认 Java serialization 生成不可读二进制。
    private final StringRedisTemplate redisTemplate;
    // 复用 Spring Boot 管理的 ObjectMapper，Java time/record 等模块配置与 MVC/Kafka JSON 保持一致。
    private final ObjectMapper objectMapper;

    public <K, V> SnapshotCache<K, V> create(
            String cacheName,
            Class<V> valueType,
            Function<K, String> keyEncoder
    ) {
        if (!properties.enabled()) {
            return new NoOpSnapshotCache<>();
        }

        SnapshotCacheProperties.CacheSpec spec = properties.cache(cacheName);
        Cache<K, V> localCache = Caffeine.newBuilder()
                // Caffeine 是 per-JVM L1；maximumSize 防止热点 key 无限增长把应用内存吃满。
                .maximumSize(spec.maximumSize())
                .expireAfterWrite(spec.localTtl())
                // recordStats 方便之后接 Actuator/Micrometer 观察 hit/miss，不需要改业务代码。
                .recordStats()
                .build();
        return new TwoLevelSnapshotCache<>(
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
