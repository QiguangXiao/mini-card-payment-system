package com.minicard.infrastructure.web.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 令牌桶 Lua 脚本的真 Redis 集成测试。
 *
 * <p>关键词：集成测试, 真 Redis, Lua 验证, integration test, real Redis,
 * token bucket, Testcontainers, 結合テスト。</p>
 *
 * <p>为什么必须真 Redis：单测里 redisTemplate 是 mock，只能验证 Java 侧分支；
 * 脚本语法、HSET 浮点 token 存储、lazy refill 计算、连续请求的原子扣减、TTL 续期
 * 这些核心算法行为只有 EVAL 到真 Redis 上才被执行到。镜像版本与 docker-compose 对齐。</p>
 *
 * <p>时间控制：脚本的 now 由 Java 侧从注入的 {@link Clock} 传入，所以"时间流逝后补令牌"
 * 不需要 sleep——换一个拨快的 fixed Clock 重建 limiter（桶状态在 Redis 里，跟着 key 走，
 * 不跟 limiter 实例走）就能确定性地验证 refill。</p>
 */
class RedisTokenBucketRateLimiterIT {

    private static final Instant T0 = Instant.parse("2026-07-08T00:00:00Z");
    /** capacity=5, refill=10/s（每 100ms 一个令牌），数值小便于逐次断言。 */
    private static final RateLimitProperties PROPERTIES = new RateLimitProperties(true, 5, 10);

    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;

    @BeforeAll
    static void startRedis() {
        REDIS.start();
        connectionFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
    }

    @AfterAll
    static void stopRedis() {
        connectionFactory.destroy();
        REDIS.stop();
    }

    @BeforeEach
    void flushBuckets() {
        // 每个测试从干净的桶状态开始，测试之间互不污染。
        try (var connection = connectionFactory.getConnection()) {
            connection.serverCommands().flushDb();
        }
    }

    @Test
    void allowsBurstUpToCapacityThenDenies() {
        RedisTokenBucketRateLimiter limiter = limiterAt(T0);

        // 冷启动满桶：前 capacity=5 次全部放行（burst 语义）。
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryConsume("client-a").allowed())
                    .as("attempt %d within capacity", i + 1)
                    .isTrue();
        }

        // 第 6 次拒绝；桶正好耗尽（tokens=0），差 1 个令牌 = 100ms（refill 10/s）。
        RateLimitDecision denied = limiter.tryConsume("client-a");
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfterMillis()).isEqualTo(100L);
    }

    @Test
    void refillsTokensAsClockAdvances() {
        limiterAt(T0).tryConsume("client-b"); // 满桶消费到 4
        exhaust(limiterAt(T0), "client-b", 4); // 消费到 0
        assertThat(limiterAt(T0).tryConsume("client-b").allowed()).isFalse();

        // 拨快 250ms：refill 2.5 个令牌 → 放行 2 次后余 0.5，第 3 次拒绝，
        // 距下一个令牌还差 0.5 个 = 50ms。浮点 refill 的正确性只能在真 Redis 上验证。
        RedisTokenBucketRateLimiter later = limiterAt(T0.plusMillis(250));
        assertThat(later.tryConsume("client-b").allowed()).isTrue();
        assertThat(later.tryConsume("client-b").allowed()).isTrue();
        RateLimitDecision denied = later.tryConsume("client-b");
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfterMillis()).isEqualTo(50L);
    }

    @Test
    void refillIsCappedAtCapacity() {
        exhaust(limiterAt(T0), "client-c", 5);

        // 拨快 1 小时：refill 远超容量，但封顶 capacity=5——只允许 5 次，不是 36000 次。
        RedisTokenBucketRateLimiter muchLater = limiterAt(T0.plus(Duration.ofHours(1)));
        exhaust(muchLater, "client-c", 5);
        assertThat(muchLater.tryConsume("client-c").allowed()).isFalse();
    }

    @Test
    void keysAreIsolatedPerClient() {
        exhaust(limiterAt(T0), "client-d", 5);

        // client-d 耗尽不影响 client-e：per-key 隔离是 per-IP 限流语义的基础。
        assertThat(limiterAt(T0).tryConsume("client-d").allowed()).isFalse();
        assertThat(limiterAt(T0).tryConsume("client-e").allowed()).isTrue();
    }

    @Test
    void bucketKeyCarriesTtl() {
        limiterAt(T0).tryConsume("client-f");

        // TTL 由脚本每次 EXPIRE 续期：空闲调用方的桶会自动清理，不会在 Redis 里永久堆积。
        Long ttl = redisTemplate.getExpire(
                RedisTokenBucketRateLimiter.KEY_PREFIX + "client-f", TimeUnit.SECONDS);
        assertThat(ttl).isPositive();
    }

    @Test
    void slowerPodClockCannotMoveTimestampBackwardAndRefillSameIntervalTwice() {
        String clientKey = "client-clock-skew";
        exhaust(limiterAt(T0), clientKey, 5); // ts=T0, tokens=0

        // 快时钟 Pod 前进 100ms，刚好补 1 个令牌并消费，Redis ts=T0+100ms。
        assertThat(limiterAt(T0.plusMillis(100)).tryConsume(clientKey).allowed()).isTrue();

        // 慢时钟 Pod 仍在 T0：应拒绝，而且不能把 Redis ts 从 T0+100ms 写回 T0。
        assertThat(limiterAt(T0).tryConsume(clientKey).allowed()).isFalse();

        // 再由快时钟在同一个 T0+100ms 判定。若上一请求让 ts 倒退，这里会重复补 100ms 并错误放行。
        assertThat(limiterAt(T0.plusMillis(100)).tryConsume(clientKey).allowed()).isFalse();
        // 真正再过去 100ms 后才应得到下一个令牌。
        assertThat(limiterAt(T0.plusMillis(200)).tryConsume(clientKey).allowed()).isTrue();
    }

    @Test
    void concurrentRequestsCannotConsumeMoreThanBucketCapacity() throws Exception {
        int capacity = 20;
        int requestCount = 100;
        RateLimitProperties properties = new RateLimitProperties(true, capacity, 10);
        RedisTokenBucketRateLimiter limiter = limiterAt(T0, properties);
        ExecutorService executor = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        try {
            for (int i = 0; i < requestCount; i++) {
                results.add(executor.submit(() -> {
                    start.await();
                    return limiter.tryConsume("client-concurrent").allowed();
                }));
            }
            start.countDown();

            long allowed = 0;
            for (Future<Boolean> result : results) {
                if (result.get()) {
                    allowed++;
                }
            }
            // Clock 固定，不发生 refill；无论 100 个请求如何交错，Lua 原子扣减都只能放行 capacity 个。
            assertThat(allowed).isEqualTo(capacity);
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private RedisTokenBucketRateLimiter limiterAt(Instant now) {
        return limiterAt(now, PROPERTIES);
    }

    private RedisTokenBucketRateLimiter limiterAt(Instant now, RateLimitProperties properties) {
        return new RedisTokenBucketRateLimiter(
                redisTemplate,
                Clock.fixed(now, ZoneOffset.UTC),
                properties,
                new SimpleMeterRegistry()
        );
    }

    private void exhaust(RedisTokenBucketRateLimiter limiter, String clientKey, int times) {
        for (int i = 0; i < times; i++) {
            assertThat(limiter.tryConsume(clientKey).allowed()).isTrue();
        }
    }
}
