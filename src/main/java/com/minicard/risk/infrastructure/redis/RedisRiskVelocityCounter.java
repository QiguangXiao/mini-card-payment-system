package com.minicard.risk.infrastructure.redis;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.minicard.risk.application.RiskProperties;
import com.minicard.risk.application.RiskVelocityCounter;
import com.minicard.risk.application.VelocityCheckResult;
import com.minicard.risk.application.VelocitySource;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

/**
 * 基于 Redis sliding window 的 velocity 计数器（分布式限流 / distributed rate limiter）。
 *
 * <p>关键词：分布式缓存, 滑动窗口限流, 原子计数, distributed cache, sliding window,
 * rate limiting, Redis ZSET, Lua atomicity, fail-open, ベロシティ制限(せいげん),
 * 分散キャッシュ(ぶんさんキャッシュ)。</p>
 *
 * <h3>为什么 velocity 适合放 Redis，而 card snapshot 不适合（这是 interview 重点）</h3>
 * <p>读缓存（read-through cache，比如缓存一张卡的快照）只有在“同一个 key 在 TTL 内被重复读”
 * 时才有收益，依赖 temporal locality；正常持卡人不会在几秒内反复刷同一张卡，所以命中率很低。</p>
 * <p>velocity 计数完全不同：它不是“缓存某次读的结果”，而是把**整个计数 workload 从主 DB 搬到 Redis**。
 * 原本每笔授权都要 `SELECT COUNT(*) FROM authorizations ...` 打一次主库——而主库正是被
 * `credit_accounts` 的 `FOR UPDATE` 占着的瓶颈资源。改成 Redis 后，**每一笔授权都少打一次主库**，
 * 收益与命中率无关、对每个请求都成立。这才是支付系统里 Redis 最对口的用法
 * （high-traffic + 分布式 + 原子计数）。</p>
 *
 * <h3>先理解 sliding window log</h3>
 * <p>Redis ZSET 是“member 唯一、按 score 排序”的集合。本实现把一次授权尝试存成一个 member，
 * 把发生时间的毫秒值存成 score；检查时删除窗口下界以前的 member，再用 ZCARD 统计剩余数量。
 * 因此它保存的是过去 N 秒内每一次真实尝试，而不是只保存一个累计数字。</p>

 * <h3>为什么用 sliding window（ZSET）而不是 fixed window（INCR）</h3>
 * <p>fixed window（按分钟 bucket `INCR`）实现简单，但有 boundary burst 问题：在窗口边界附近，
 * 攻击者可以在前一个窗口末尾和后一个窗口开头各打满，瞬时速率达到上限的 2 倍。
 * sliding window 用一个 ZSET 存“每次尝试的时间戳”，每次查询都把窗口外的旧记录裁掉再计数，
 * 任意时刻看的都是真正过去 N 秒的次数，没有边界突刺。</p>
 *
 * <h3>为什么必须用 Lua 保证原子（counterfactual）</h3>
 * <p>“裁剪旧记录 → 添加本次 → 计数 → 续期”是四步。如果不放进一个 Lua 脚本里原子执行，
 * 同一张卡的并发授权会在 ZADD 和 ZCARD 之间交错，导致计数偏小、限流被绕过。
 * Redis 单线程执行整段 Lua，等于给这张卡的窗口操作上了一把无需显式锁的原子门。</p>
 */
@Repository
// 默认启用 Redis 实现（matchIfMissing=true）；把 risk.velocity.store 设成 jdbc 可切回
// JdbcRiskVelocityCounter 做对照。两个实现用同一个 RiskVelocityCounter port，互斥注入。
@ConditionalOnProperty(
        prefix = "risk.velocity",
        name = "store",
        havingValue = "redis",
        matchIfMissing = true
)
@Slf4j
public class RedisRiskVelocityCounter implements RiskVelocityCounter {

    /** ZSET key 前缀；一张卡一个窗口 key，例如 risk:velocity:card-123。 */
    private static final String KEY_PREFIX = "risk:velocity:";

    /**
     * 滑动窗口的原子操作脚本。
     *
     * <p>KEYS[1]=窗口 key；ARGV[1]=now(ms,作为新成员的 score)；ARGV[2]=窗口下界(ms)；
     * ARGV[3]=唯一成员；ARGV[4]=key 的 TTL 秒数。返回窗口内（含本次）的尝试数。</p>
     */
    private static final RedisScript<Long> SLIDING_WINDOW = RedisScript.of(
            // Lua 基础：KEYS[1] 是这张卡的 Redis key；ARGV[] 是 Java 传入的时间、member 和 TTL。
            // ZADD 把“本次尝试”按时间戳记入有序集合。
            "redis.call('ZADD', KEYS[1], ARGV[1], ARGV[3])\n" // score=发生时间，member=本次尝试的唯一标识。
            // 裁掉 score < 窗口下界 的旧尝试（'(' 表示开区间，保留正好等于下界的）。
            + "redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', '(' .. ARGV[2])\n" // 删除窗口下界以前的旧尝试。
            // 剩下的就是窗口内的尝试数（包含本次）。
            + "local count = redis.call('ZCARD', KEYS[1])\n" // ZCARD 返回裁剪后仍在窗口内的 member 数。
            // 给整个 key 续期：长时间没有授权的卡，其窗口 key 会自动过期，避免 Redis 内存泄漏。
            // 如果不 EXPIRE，海量历史卡的空窗口 key 会永久堆积。
            + "redis.call('EXPIRE', KEYS[1], ARGV[4])\n" // 续期整个 ZSET；长期不再刷卡的 key 会自动消失。
            + "return count",                            // 把含本次尝试的窗口计数返回 Java。
            Long.class
    );

    private final StringRedisTemplate redisTemplate;
    private final Clock clock;
    private final RiskProperties properties;
    private final MeterRegistry meterRegistry;

    public RedisRiskVelocityCounter(
            StringRedisTemplate redisTemplate,
            Clock clock,
            RiskProperties properties,
            MeterRegistry meterRegistry
    ) {
        this.redisTemplate = redisTemplate;
        this.clock = clock;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    /**
     * 记录本次授权尝试并返回窗口内（含本次）的总数。
     *
     * <p>与 JdbcRiskVelocityCounter 的语义差异（interview 可讲）：JDBC 版是“读” authorizations 表
     * （该表由 authorization claim 写入，是 audit source of truth）；Redis 版自带一份**近似**滑动窗口，
     * 在本方法里“记录 + 计数”。用近似换主库零读压，是典型的 high-traffic 取舍。</p>
     */
    @Override
    public VelocityCheckResult countRecentAuthorizations(String cardId, Instant since) {
        // Clock 可在测试中固定；now 同时用于 ZSET score 和唯一 member 的可读前缀。
        Instant now = Instant.now(clock);
        // 唯一成员：同一毫秒内的并发尝试 score 可能相同，ZSET 成员必须唯一，否则会被去重导致少计。
        String member = now.toEpochMilli() + "-" + UUID.randomUUID();
        // TTL 取窗口长度 + 1 秒缓冲：窗口内的记录每次都会被裁剪，TTL 只负责清理空闲卡的 key。
        long ttlSeconds = properties.local().velocityWindowSeconds() + 1;

        try {
            // 一次 execute 对 Redis 来说是一段不可插入其他命令的 Lua 执行；Java 侧不需要再加 JVM 锁。
            Long count = redisTemplate.execute(
                    SLIDING_WINDOW,                        // 要执行的脚本，声明返回 Long count。
                    List.of(KEY_PREFIX + cardId),          // KEYS[1]：一张卡对应一个 ZSET。
                    Long.toString(now.toEpochMilli()),     // ARGV[1]：本次 member 的 score。
                    Long.toString(since.toEpochMilli()),   // ARGV[2]：滑动窗口下界。
                    member,                               // ARGV[3]：防同毫秒去重的唯一 member。
                    Long.toString(ttlSeconds)              // ARGV[4]：空闲 ZSET 的 TTL 秒数。
            );
            return VelocityCheckResult.available(count == null ? 0 : count.intValue(), VelocitySource.REDIS);
        } catch (DataAccessException exception) {
            meterRegistry.counter("risk.velocity.redis.unavailable").increment();
            // Fail-open：Redis 暂时不可用时返回 degraded result，而不是让授权失败。
            // counterfactual：如果 fail-closed（把异常抛出去），一次 Redis 抖动会让**全系统所有授权**
            // 都被拒——为了一个非关键的风控信号牺牲整体可用性，得不偿失。velocity 只是多个风控规则之一，
            // 限额校验、external risk、credit limit 仍然生效，所以这里选择降级放行并告警。
            // 注意这里不回查 DB：Redis 故障时全量 fallback 到 COUNT(*) 会把 DB/Hikari 一起拖进 brownout。
            log.warn("risk_velocity_redis_unavailable cardId={} fallback=allow", cardId, exception);
            return VelocityCheckResult.degradedAllow(VelocitySource.REDIS);
        }
    }
}
