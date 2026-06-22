package com.minicard.infrastructure.cache;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.function.Supplier;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Caffeine L1 + Redis L2 的 read-through cache。
 *
 * <p>关键词：两级缓存, 缓存穿透保护, Caffeine L1, Redis L2,
 * read-through cache, single-flight, 二段階キャッシュ(にだんかいキャッシュ),
 * 読み込みキャッシュ(よみこみキャッシュ)。</p>
 *
 * <p>L1 负责同 JVM 热点读取，L2 负责跨实例共享。MySQL 仍然是 source of truth：
 * Redis 失败、JSON 损坏或 cache miss 都会回到 loader，不让低风险 cache 影响主查询可用性。</p>
 */
@Slf4j
final class TwoLevelSnapshotCache<K, V> implements SnapshotCache<K, V> {

    private static final String KEY_SEPARATOR = ":";

    private final String keyPrefix;
    private final String cacheName;
    private final SnapshotCacheProperties.CacheSpec spec;
    private final Cache<K, V> localCache;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Class<V> valueType;
    private final Function<K, String> keyEncoder;
    private final ConcurrentHashMap<K, Object> localLocks = new ConcurrentHashMap<>();

    TwoLevelSnapshotCache(
            String keyPrefix,
            String cacheName,
            SnapshotCacheProperties.CacheSpec spec,
            Cache<K, V> localCache,
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            Class<V> valueType,
            Function<K, String> keyEncoder
    ) {
        this.keyPrefix = requireText(keyPrefix, "cache keyPrefix must not be blank");
        this.cacheName = requireText(cacheName, "cacheName must not be blank");
        this.spec = Objects.requireNonNull(spec, "cache spec must not be null");
        this.localCache = Objects.requireNonNull(localCache, "local cache must not be null");
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redis template must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "object mapper must not be null");
        this.valueType = Objects.requireNonNull(valueType, "cache valueType must not be null");
        this.keyEncoder = Objects.requireNonNull(keyEncoder, "cache keyEncoder must not be null");
    }

    @Override
    public V get(K key, Supplier<V> loader) {
        Objects.requireNonNull(key, "cache key must not be null");
        Objects.requireNonNull(loader, "cache loader must not be null");

        V localValue = localCache.getIfPresent(key);
        if (localValue != null) {
            return localValue;
        }

        // 同一个 JVM 内做 per-key single-flight，避免一个热点 key 在 L1/L2 同时 miss 时并发打爆 DB。
        // 跨 pod 的 cache stampede 仍可通过较长 Redis TTL + TTL jitter 缓解；真正超热点可再引入 Redis mutex。
        // 如果没有这把本地 per-key lock，同一个 statement/card 热点 miss 会让几十个线程同时回源 MySQL。
        Object lock = localLocks.computeIfAbsent(key, ignored -> new Object());
        try {
            synchronized (lock) {
                localValue = localCache.getIfPresent(key);
                if (localValue != null) {
                    return localValue;
                }

                Optional<V> remoteValue = readRemote(key);
                if (remoteValue.isPresent()) {
                    localCache.put(key, remoteValue.get());
                    return remoteValue.get();
                }

                V loaded = loader.get();
                if (loaded != null) {
                    put(key, loaded);
                }
                return loaded;
            }
        } finally {
            localLocks.remove(key, lock);
        }
    }

    @Override
    public void evict(K key) {
        Objects.requireNonNull(key, "cache key must not be null");
        localCache.invalidate(key);
        String redisKey = redisKey(key);
        try {
            redisTemplate.delete(redisKey);
        } catch (RuntimeException exception) {
            // evict 失败时当前 JVM 的 L1 已经清掉；Redis 短 TTL 兜底，查询仍以 DB 为准。
            log.warn(
                    "Redis evict failed for snapshot cache {} key {}; local cache was invalidated",
                    cacheName,
                    redisKey,
                    exception
            );
        }
    }

    private Optional<V> readRemote(K key) {
        String redisKey = redisKey(key);
        try {
            String json = redisTemplate.opsForValue().get(redisKey);
            if (json == null) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(json, valueType));
        } catch (JsonProcessingException exception) {
            // Redis 里出现无法反序列化的旧 value 时直接删除，让下一次从 DB 重建。
            // 否则一个坏 JSON 会让所有后续查询不断失败。
            deleteCorruptRemoteValue(redisKey);
            log.warn(
                    "Invalid JSON in snapshot cache {} key {}; evicted Redis value and reloading from DB",
                    cacheName,
                    redisKey,
                    exception
            );
            return Optional.empty();
        } catch (RuntimeException exception) {
            // Redis 只是性能层；如果这里把异常抛给业务 GET，就会让 cache 故障扩大成 API 故障。
            log.warn(
                    "Redis read failed for snapshot cache {} key {}; falling back to DB",
                    cacheName,
                    redisKey,
                    exception
            );
            return Optional.empty();
        }
    }

    private void put(K key, V value) {
        localCache.put(key, value);
        String redisKey = redisKey(key);
        try {
            String json = objectMapper.writeValueAsString(value);
            redisTemplate.opsForValue().set(redisKey, json, remoteTtlWithJitter());
        } catch (JsonProcessingException exception) {
            // 序列化失败通常代表 snapshot 设计问题；不写 Redis，但 L1 和 DB 查询仍可工作。
            log.warn(
                    "Failed to serialize snapshot cache {} key {}; Redis write skipped",
                    cacheName,
                    redisKey,
                    exception
            );
        } catch (RuntimeException exception) {
            // 写 L2 失败不能影响本次 response；否则缓存系统会反过来决定业务可用性。
            log.warn(
                    "Redis write failed for snapshot cache {} key {}; local cache still holds value",
                    cacheName,
                    redisKey,
                    exception
            );
        }
    }

    private Duration remoteTtlWithJitter() {
        Duration jitter = spec.remoteTtlJitter();
        if (jitter.isZero()) {
            return spec.remoteTtl();
        }
        long jitterMillis = jitter.toMillis();
        long extraMillis = ThreadLocalRandom.current().nextLong(jitterMillis + 1);
        return spec.remoteTtl().plusMillis(extraMillis);
    }

    private String redisKey(K key) {
        String encodedKey = requireText(
                keyEncoder.apply(key),
                "encoded cache key must not be blank"
        );
        return keyPrefix
                + KEY_SEPARATOR + "cache"
                + KEY_SEPARATOR + cacheName
                + KEY_SEPARATOR + encodedKey;
    }

    private void deleteCorruptRemoteValue(String redisKey) {
        try {
            redisTemplate.delete(redisKey);
        } catch (RuntimeException exception) {
            log.warn(
                    "Failed to delete corrupt Redis value for snapshot cache {} key {}",
                    cacheName,
                    redisKey,
                    exception
            );
        }
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }
}
