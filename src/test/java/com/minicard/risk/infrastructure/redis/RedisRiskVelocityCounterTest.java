package com.minicard.risk.infrastructure.redis;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.minicard.risk.application.RiskProperties;
import com.minicard.risk.application.VelocityCheckResult;
import com.minicard.risk.application.VelocitySource;
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

class RedisRiskVelocityCounterTest {

    private static final Instant NOW = Instant.parse("2026-06-27T00:01:00Z");
    private static final Instant SINCE = NOW.minusSeconds(60);

    private StringRedisTemplate redisTemplate;
    private SimpleMeterRegistry meterRegistry;
    private RedisRiskVelocityCounter counter;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        meterRegistry = new SimpleMeterRegistry();
        counter = new RedisRiskVelocityCounter(
                redisTemplate,
                Clock.fixed(NOW, ZoneOffset.UTC),
                riskProperties(),
                meterRegistry
        );
    }

    @Test
    @SuppressWarnings("unchecked")
    void recordsAttemptAndReturnsSlidingWindowCount() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any(), any()))
                .thenReturn(2L);

        VelocityCheckResult result = counter.countRecentAuthorizations("card-1", SINCE);

        assertThat(result.count()).isEqualTo(2);
        assertThat(result.degraded()).isFalse();
        assertThat(result.source()).isEqualTo(VelocitySource.REDIS);
        // 每张卡一个窗口 key；这样不同卡的限流互不影响。
        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        verify(redisTemplate).execute(any(RedisScript.class), keys.capture(), any(), any(), any(), any());
        assertThat(keys.getValue()).containsExactly("risk:velocity:card-1");
    }

    @Test
    @SuppressWarnings("unchecked")
    void failsOpenWhenRedisIsUnavailable() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any(), any()))
                .thenThrow(new RedisConnectionFailureException("redis down"));

        // Redis 不可用时降级放行（返回 degraded 结果），不能让一次 Redis 抖动拒绝所有授权。
        VelocityCheckResult result = counter.countRecentAuthorizations("card-1", SINCE);

        assertThat(result.count()).isZero();
        assertThat(result.degraded()).isTrue();
        assertThat(result.source()).isEqualTo(VelocitySource.REDIS);
        assertThat(meterRegistry.counter("risk.velocity.redis.unavailable").count()).isEqualTo(1.0);
    }

    private RiskProperties riskProperties() {
        return new RiskProperties(
                new RiskProperties.Local(
                        60,
                        3,
                        5,
                        new BigDecimal("0.80"),
                        Map.of("JPY", new BigDecimal("50000.00")),
                        Set.of("merchant-blocked")
                ),
                new RiskProperties.External("http://localhost:8080", 100, 0, 80)
        );
    }
}
