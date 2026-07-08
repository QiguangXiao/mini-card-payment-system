package com.minicard.infrastructure.web.ratelimit;

import java.time.Clock;
import java.util.List;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * 基于 Redis 令牌桶的分布式 API 限流器。
 *
 * <p>关键词：令牌桶, 分布式限流, Lua 原子, token bucket, distributed rate limiter,
 * lazy refill, fail-open, 流量制限(りゅうりょうせいげん), トークンバケット。</p>
 *
 * <h3>为什么是令牌桶，而不是复用 velocity 的 ZSET 滑动窗口（interview 对比点）</h3>
 * <p>两者回答的问题不同。滑动窗口回答"过去 N 秒内发生了几次"，适合风控这种要精确回看
 * 行为次数的场景，代价是内存 O(窗口内尝试数)。令牌桶回答"现在还有没有余量"，
 * 用 capacity 显式表达"允许多大突发"、refill 速率表达"允许多高持续吞吐"，
 * 状态只有 {tokens, ts} 两个数字，内存 O(1)。API 保护恰恰需要"允许合理突发、
 * 压住持续洪峰"的语义，且 key 维度（调用方）基数大，O(1) 状态更合适。</p>
 *
 * <h3>lazy refill：没有后台补令牌的线程</h3>
 * <p>桶不靠定时任务补令牌，而是在每次请求到达时按 elapsed × 速率一次性补算。
 * 这让任意多个桶都零后台成本——这也是所有生产级令牌桶（Guava RateLimiter、Bucket4j）
 * 的共同实现方式。</p>
 *
 * <h3>为什么必须 Lua（和 velocity 同一个论证）</h3>
 * <p>"读状态 → 补令牌 → 扣减 → 写回"是四步。两个并发请求如果交错执行，会基于同一份
 * 旧 tokens 各自扣减再写回，导致少计、限流被绕过。Redis 单线程执行整段 Lua，
 * 等于给这个 key 的桶操作上了无锁的原子门。</p>
 */
@Slf4j
public class RedisTokenBucketRateLimiter {

    /** 每个调用方一个桶 key，例如 ratelimit:authorization:203.0.113.7。 */
    static final String KEY_PREFIX = "ratelimit:authorization:";

    /**
     * 令牌桶的原子操作脚本。
     *
     * <p>KEYS[1]=桶 key；ARGV[1]=capacity；ARGV[2]=每毫秒补充令牌数；
     * ARGV[3]=now(ms)；ARGV[4]=key TTL 秒数。
     * 返回 0 表示放行（已扣减 1 个令牌）；返回正数表示拒绝，值为距下一个令牌可用的毫秒数。</p>
     */
    private static final RedisScript<Long> TOKEN_BUCKET = RedisScript.of(
            // HMGET 一次取出桶状态；key 不存在时两个字段都是 nil。
            "local state = redis.call('HMGET', KEYS[1], 'tokens', 'ts')\n"
            + "local capacity = tonumber(ARGV[1])\n"
            + "local refill_per_ms = tonumber(ARGV[2])\n"
            + "local now = tonumber(ARGV[3])\n"
            + "local tokens = tonumber(state[1])\n"
            + "local ts = tonumber(state[2])\n"
            // 新调用方（或 TTL 过期后的老调用方）从满桶冷启动：第一批请求允许打满突发容量。
            + "if tokens == nil or ts == nil then\n"
            + "  tokens = capacity\n"
            + "  ts = now\n"
            + "end\n"
            // lazy refill：按上次访问以来的毫秒数补令牌，封顶 capacity。
            // elapsed 下限 0：多实例时钟略有偏差时，避免负 elapsed 把令牌越补越少。
            + "local elapsed = now - ts\n"
            + "if elapsed < 0 then elapsed = 0 end\n"
            + "tokens = tokens + elapsed * refill_per_ms\n"
            + "if tokens > capacity then tokens = capacity end\n"
            + "local wait = 0\n"
            + "if tokens >= 1 then\n"
            + "  tokens = tokens - 1\n"
            + "else\n"
            // 不足 1 个令牌时不扣减，只算出"还差的部分按速率要等多久"，给客户端 Retry-After 用。
            + "  wait = math.ceil((1 - tokens) / refill_per_ms)\n"
            + "end\n"
            // 拒绝时也要写回：refill 已经推进到 now，不写回会在下次重复补同一段时间。
            + "redis.call('HSET', KEYS[1], 'tokens', tokens, 'ts', now)\n"
            // TTL 清理空闲调用方的桶 key；过期后重建 = 满桶，与"闲置足够久桶自然灌满"语义一致。
            + "redis.call('EXPIRE', KEYS[1], tonumber(ARGV[4]))\n"
            + "return wait",
            Long.class
    );

    private final StringRedisTemplate redisTemplate;
    private final Clock clock;
    private final RateLimitProperties properties;
    private final MeterRegistry meterRegistry;
    /** 桶从空补到满所需秒数的两倍（下限 60s）：闲置超过这个时长后，"过期重建=满桶"不改变语义。 */
    private final long ttlSeconds;

    public RedisTokenBucketRateLimiter(
            StringRedisTemplate redisTemplate,
            Clock clock,
            RateLimitProperties properties,
            MeterRegistry meterRegistry
    ) {
        this.redisTemplate = redisTemplate;
        this.clock = clock;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.ttlSeconds = Math.max(
                60,
                (long) Math.ceil(properties.capacity() / properties.refillPerSecond()) * 2
        );
    }

    /**
     * 尝试为该调用方消费一个令牌。
     *
     * <p>clientKey 由 web 层解析（当前是客户端 IP）；本类只负责"给定 key 的桶还有没有余量"，
     * 不关心 key 是怎么来的——维度策略变化（比如换成认证后的 client id）不影响这里。</p>
     */
    public RateLimitDecision tryConsume(String clientKey) {
        long nowMillis = clock.millis();
        try {
            Long waitMillis = redisTemplate.execute(
                    TOKEN_BUCKET,
                    List.of(KEY_PREFIX + clientKey),
                    Integer.toString(properties.capacity()),
                    // 速率换算成"每毫秒"传入，脚本内不再做单位换算。
                    Double.toString(properties.refillPerSecond() / 1000d),
                    Long.toString(nowMillis),
                    Long.toString(ttlSeconds)
            );
            if (waitMillis == null || waitMillis <= 0) {
                return RateLimitDecision.allow();
            }
            return RateLimitDecision.deny(waitMillis);
        } catch (DataAccessException exception) {
            meterRegistry.counter("api.ratelimit.redis.unavailable").increment();
            // Fail-open：与 RedisRiskVelocityCounter 同一个论证——限流是保护手段而不是业务依赖，
            // 一次 Redis 抖动如果 fail-closed，会把"防洪闸"变成"全站断路器"，比洪峰本身伤害更大。
            // 真正的洪峰兜底还有 Tomcat 线程池和 bulkhead 这两层物理闸门。
            log.warn("api_rate_limit_redis_unavailable clientKey={} fallback=allow", clientKey, exception);
            return RateLimitDecision.degradedAllow();
        }
    }
}
