package com.minicard.infrastructure.web.ratelimit;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisTokenBucketRateLimiterTest {

    private static final Instant NOW = Instant.parse("2026-07-08T00:01:00Z");

    private StringRedisTemplate redisTemplate;
    private SimpleMeterRegistry meterRegistry;
    private RedisTokenBucketRateLimiter limiter;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        meterRegistry = new SimpleMeterRegistry();
        limiter = new RedisTokenBucketRateLimiter(
                redisTemplate,
                Clock.fixed(NOW, ZoneOffset.UTC),
                new RateLimitProperties(true, 20, 10),
                meterRegistry
        );
    }

    @Test
    @SuppressWarnings("unchecked")
    void allowsWhenScriptReturnsZeroWait() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any(), any()))
                .thenReturn(0L);

        RateLimitDecision decision = limiter.tryConsume("203.0.113.7");

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.degraded()).isFalse();
        // 每个调用方一个桶 key；不同调用方的限流互不影响。
        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        verify(redisTemplate).execute(any(RedisScript.class), keys.capture(), any(), any(), any(), any());
        assertThat(keys.getValue()).containsExactly("ratelimit:authorization:203.0.113.7");
    }

    @Test
    @SuppressWarnings("unchecked")
    void deniesWithRetryAfterWhenBucketIsEmpty() {
        // 脚本返回正数 = 拒绝，值为距下一个令牌可用的毫秒数。
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any(), any()))
                .thenReturn(1500L);

        RateLimitDecision decision = limiter.tryConsume("203.0.113.7");

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.retryAfterMillis()).isEqualTo(1500L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void failsOpenWhenRedisIsUnavailable() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any(), any()))
                .thenThrow(new RedisConnectionFailureException("redis down"));

        // Redis 不可用时降级放行：限流是保护手段，不能让 Redis 抖动变成全站 429。
        RateLimitDecision decision = limiter.tryConsume("203.0.113.7");

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.degraded()).isTrue();
        assertThat(meterRegistry.counter("api.ratelimit.redis.unavailable").count()).isEqualTo(1.0);
    }

    @Test
    void rejectsNonPositiveConfiguration() {
        // record compact constructor 在绑定期 fail fast，坏配置应用启动即失败。
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new RateLimitProperties(true, 0, 10))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new RateLimitProperties(true, 20, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
