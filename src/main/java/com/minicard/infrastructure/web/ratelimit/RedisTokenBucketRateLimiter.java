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
 * <h3>先理解 token bucket</h3>
 * <p>可以把每个调用方想成一个装令牌的桶：请求必须先拿走 1 个令牌；桶以固定速率补充，
 * 但最多装到 {@code capacity}。所以 capacity 控制短时 burst，refill rate 控制长期吞吐。
 * 本实现为每个 client key 保存两个字段：当前 {@code tokens} 和上次计算时间 {@code ts}。</p>

 * <h3>为什么不复用 risk velocity 的 ZSET 滑动窗口（interview 对比点）</h3>
 * <p>两者回答的问题不同。滑动窗口回答“过去 N 秒发生了几次”，适合风控精确回看行为次数，
 * 代价是保存窗口内每次尝试。令牌桶只回答“现在是否还有通行额度”，状态固定为
 * {@code {tokens, ts}}，更适合允许短 burst、压住持续洪峰的 API 保护。</p>
 *
 * <h3>lazy refill：为什么没有定时任务</h3>
 * <p>Redis 不会每秒遍历所有调用方补令牌。请求到达时才计算
 * {@code elapsed × refillRate}，一次性补上这段时间应得的令牌。没有访问的桶不做计算，
 * 最后由 TTL 清理，因此 key 数量增加时也不需要增加后台补充线程。</p>
 *
 * <h3>为什么必须 Lua</h3>
 * <p>如果 Java 分四次发命令完成“读状态 → 补令牌 → 扣减 → 写回”，两个 Pod 可能同时读到
 * 同一份旧 tokens，各自放行后再互相覆盖结果。Lua 把多条 Redis 命令变成一次原子执行：
 * 脚本运行期间不会插入另一个请求的桶操作，因此不会因 read-modify-write 竞争而少扣令牌。</p>
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
            // Lua 基础：KEYS[] 放 Redis key，ARGV[] 放普通参数；脚本只能操作显式传入的 key。
            // HMGET 一次取出桶状态；key 不存在时两个字段都是 nil。
            "local state = redis.call('HMGET', KEYS[1], 'tokens', 'ts')\n" // 一次读取 HASH 中的令牌数和时间戳。
            + "local capacity = tonumber(ARGV[1])\n"                      // ARGV 都是字符串，先转成 Lua number。
            + "local refill_per_ms = tonumber(ARGV[2])\n"                // 每毫秒补多少令牌，允许小数。
            + "local now = tonumber(ARGV[3])\n"                          // 当前请求时间，由 Java Clock 传入。
            + "local tokens = tonumber(state[1])\n"                      // key 不存在时 state[1] 为 nil。
            + "local ts = tonumber(state[2])\n"                          // 上一次计算 refill 的时间点。
            // 新调用方（或 TTL 过期后的老调用方）从满桶冷启动：第一批请求允许打满突发容量。
            + "if tokens == nil or ts == nil then\n"                    // 第一次访问或 TTL 过期后进入初始化分支。
            + "  tokens = capacity\n"                                   // 新桶从满桶开始，允许一次 capacity burst。
            + "  ts = now\n"                                            // 从当前时刻开始累计后续 refill。
            + "end\n"                                                   // 结束初始化分支。
            // lazy refill：按上次访问以来的毫秒数补令牌，封顶 capacity。
            // elapsed 下限 0：多实例时钟略有偏差时，避免负 elapsed 把令牌越补越少。
            + "local elapsed = now - ts\n"                               // 计算距离上次访问过去了多少毫秒。
            + "if elapsed < 0 then elapsed = 0 end\n"                    // 防止负时间直接扣掉已有令牌。
            + "tokens = tokens + elapsed * refill_per_ms\n"              // lazy refill：到请求到来时才补算令牌。
            + "if tokens > capacity then tokens = capacity end\n"        // 桶不能超过容量，否则长时间闲置会无限累积。
            + "local wait = 0\n"                                         // 0 是“本次放行”的返回约定。
            + "if tokens >= 1 then\n"                                    // 至少有一个完整令牌才允许请求通过。
            + "  tokens = tokens - 1\n"                                  // 消费一个令牌；扣减也在同一原子脚本内。
            + "else\n"                                                   // 不足一个令牌，进入拒绝和等待时间计算。
            // 不足 1 个令牌时不扣减，只算出"还差的部分按速率要等多久"，给客户端 Retry-After 用。
            + "  wait = math.ceil((1 - tokens) / refill_per_ms)\n"       // 还缺多少令牌 / 补充速率 = 等待毫秒数。
            + "end\n"                                                   // 结束 allow/deny 分支。
            // 拒绝时也要写回：refill 已经推进到 now，不写回会在下次重复补同一段时间。
            + "redis.call('HSET', KEYS[1], 'tokens', tokens, 'ts', now)\n" // 把本次计算后的两个状态一起写回 HASH。
            // TTL 清理空闲调用方的桶 key；过期后重建 = 满桶，与"闲置足够久桶自然灌满"语义一致。
            + "redis.call('EXPIRE', KEYS[1], tonumber(ARGV[4]))\n"       // 每次访问续 TTL，空闲 client 最终自动清理。
            + "return wait",                                             // Java 用 0/正毫秒数映射 allow/deny。
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
        // Java Clock 由 Spring 注入，单元/集成测试可固定时间，不需要 sleep 等待 refill。
        long nowMillis = clock.millis();
        try {
            Long waitMillis = redisTemplate.execute(
                    TOKEN_BUCKET,                            // 要执行的脚本及其 Long 返回类型。
                    List.of(KEY_PREFIX + clientKey),         // KEYS[1]：该调用方唯一的桶 key。
                    Integer.toString(properties.capacity()), // ARGV[1]：桶容量。
                    // 速率换算成"每毫秒"传入，脚本内不再做单位换算。
                    Double.toString(properties.refillPerSecond() / 1000d), // ARGV[2]：每毫秒 refill。
                    Long.toString(nowMillis),                              // ARGV[3]：本次判定时间。
                    Long.toString(ttlSeconds)                              // ARGV[4]：桶 key 的 TTL 秒数。
            );
            // execute() 会把 Lua return 转成 Long：0=已消费并放行，正数=还需等待多少毫秒。
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
