package com.minicard.infrastructure.cache;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TwoLevelReadModelCacheTest {

    private static final String REDIS_KEY = "mini-card:cache:test-cache-v1:statement-1";

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private ObjectMapper objectMapper;
    private TwoLevelReadModelCache<String, CachedView> cache;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        objectMapper = JsonMapper.builder()
                .findAndAddModules()
                .build();
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        cache = new TwoLevelReadModelCache<>(
                "mini-card",
                "test-cache-v1",
                new ReadModelCacheProperties.CacheSpec(
                        Duration.ofSeconds(30),
                        100,
                        Duration.ofMinutes(5),
                        Duration.ZERO
                ),
                Caffeine.newBuilder()
                        .maximumSize(100)
                        .expireAfterWrite(Duration.ofSeconds(30))
                        .build(),
                redisTemplate,
                objectMapper,
                CachedView.class,
                key -> key
        );
    }

    @Test
    void loadsFromSourceAndStoresInLocalAndRedisWhenBothLevelsMiss() {
        when(valueOperations.get(REDIS_KEY)).thenReturn(null);
        AtomicInteger loads = new AtomicInteger();

        CachedView first = cache.get("statement-1", () -> {
            loads.incrementAndGet();
            return new CachedView("statement-1", Instant.parse("2026-07-01T00:00:00Z"));
        });
        CachedView second = cache.get("statement-1", () -> {
            loads.incrementAndGet();
            return new CachedView("unexpected", Instant.EPOCH);
        });

        assertThat(first.id()).isEqualTo("statement-1");
        assertThat(second).isEqualTo(first);
        assertThat(loads).hasValue(1);
        verify(valueOperations, times(1)).get(REDIS_KEY);
        verify(valueOperations).set(eq(REDIS_KEY), anyString(), any(Duration.class));
    }

    @Test
    void readsRedisAndBackfillsLocalCache() throws Exception {
        CachedView remote = new CachedView(
                "statement-1",
                Instant.parse("2026-07-01T00:00:00Z")
        );
        when(valueOperations.get(REDIS_KEY)).thenReturn(objectMapper.writeValueAsString(remote));

        CachedView first = cache.get("statement-1", () -> {
            throw new AssertionError("source loader should not be called on Redis hit");
        });
        CachedView second = cache.get("statement-1", () -> {
            throw new AssertionError("source loader should not be called after L1 backfill");
        });

        assertThat(first).isEqualTo(remote);
        assertThat(second).isEqualTo(remote);
        verify(valueOperations, times(1)).get(REDIS_KEY);
    }

    @Test
    void evictsLocalAndRedisCache() {
        when(valueOperations.get(REDIS_KEY)).thenReturn(null);
        cache.get("statement-1", () -> new CachedView(
                "statement-1",
                Instant.parse("2026-07-01T00:00:00Z")
        ));
        clearInvocations(redisTemplate, valueOperations);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        cache.evict("statement-1");
        AtomicInteger loads = new AtomicInteger();
        cache.get("statement-1", () -> {
            loads.incrementAndGet();
            return new CachedView("statement-1", Instant.parse("2026-07-01T00:00:00Z"));
        });

        verify(redisTemplate).delete(REDIS_KEY);
        assertThat(loads).hasValue(1);
    }

    @Test
    void fallsBackToSourceWhenRedisReadFails() {
        when(valueOperations.get(REDIS_KEY)).thenThrow(new IllegalStateException("redis unavailable"));
        AtomicInteger loads = new AtomicInteger();

        CachedView value = cache.get("statement-1", () -> {
            loads.incrementAndGet();
            return new CachedView("statement-1", Instant.parse("2026-07-01T00:00:00Z"));
        });

        assertThat(value.id()).isEqualTo("statement-1");
        assertThat(loads).hasValue(1);
    }

    private record CachedView(String id, Instant generatedAt) {
    }
}
