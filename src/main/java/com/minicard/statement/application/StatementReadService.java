package com.minicard.statement.application;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Statement GET 的 cache-aside 查询服务。
 *
 * <p>关键词：账单查询, Caffeine L1, Redis L2, after-commit eviction,
 * statement read cache, cache-aside, 請求照会(せいきゅうしょうかい),
 * 二段階キャッシュ(にだんかいキャッシュ)。</p>
 *
 * <p>这里刻意只服务单张 statement GET，不做通用 cache framework：目的是学习两级缓存最核心的
 * 读路径和失效顺序。Caffeine 是同 JVM L1，Redis 是跨实例 L2，MySQL 仍是 source of truth。</p>
 *
 * <p>生产 cache 最重要的第一条边界：cache 只是 performance layer，不是正确性来源。
 * 本类缓存的是 `StatementReadModel` 这种可从 MySQL 重建的查询快照；额度、幂等 winner、
 * 还款入账结果仍然必须由 DB transaction / row lock 保护。</p>
 *
 * <p>当前采用 cache-aside：读的时候先看 cache，miss 后回源 DB，再把结果写回 cache。
 * 写的时候不直接更新 cache，而是更新 DB 后删除 cache，让下一次读重新从 DB 构造。
 * 这比“写 DB 同时更新 cache”更容易解释和排查，因为并发下更新 cache 很容易写入旧计算结果。</p>
 */
@Service
@Slf4j
public class StatementReadService {

    // cache name 带版本号。Redis value 是 JSON contract；字段语义不兼容时换名字比全量清 Redis 更安全。
    private static final String CACHE_NAME = "statement-read-model-v1";
    private static final String REDIS_KEY_PREFIX = "mini-card:cache:" + CACHE_NAME + ":";

    private final StatementGenerationService statementGenerationService;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final StatementReadCacheProperties properties;
    // L1 是每个 JVM 自己的本地内存 cache，速度快但不跨 pod 共享。
    // 所以 local TTL 必须比 Redis 短，避免其他 pod 写完后，本 pod 长时间拿旧值。
    private final Cache<UUID, StatementReadModel> localCache;

    public StatementReadService(
            StatementGenerationService statementGenerationService,
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            StatementReadCacheProperties properties
    ) {
        this.statementGenerationService = statementGenerationService;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.localCache = Caffeine.newBuilder()
                // maximumSize 是生产 cache 的基本护栏：没有上限时，热点或恶意 key 会吃满 JVM heap。
                .maximumSize(properties.localMaximumSize())
                // expireAfterWrite 让每个 pod 的 L1 自然过期；它不是强一致失效，只是 stale window 的上限。
                .expireAfterWrite(properties.localTtl())
                .build();
    }

    public StatementReadModel get(UUID id) {
        // 入口只问 L1。L1 miss 时，Caffeine 才调用 loadThroughRedis。
        // Cache.get(key, mappingFunction) 自带同 JVM per-key single-flight：
        // 同一个 statementId 同时 miss 时，只有一个线程进入 L2/DB loader，其他线程等结果。
        // 这只能保护本 JVM；多个 pod 同时 miss，仍可能各自回源，所以 Redis L2 和 TTL jitter 仍然需要。
        return localCache.get(id, this::loadThroughRedis);
    }

    public void evictAfterCommit(UUID statementId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            // 没有 Spring transaction 时直接删，通常用于测试或未来非事务调用。
            evict(statementId);
            return;
        }
        // 还款 transaction commit 后再删缓存。afterCommit 的含义是：只有 DB 真的提交成功，
        // cache 才需要失效；rollback 时不做多余删除。在事务内提前 evict 会更糟——删完之后、commit 之前，
        // 另一个 GET 仍读到旧 DB 值并写回 Redis，旧值反而被“固化”。所以失效一定排到 commit 之后。
        //
        // 重要：afterCommit 只是“写侧的删除时机”，它并不能消除 cache-aside 固有的 read-write 竞态。
        // 仍然存在的 stale window（delete-after-commit 无法解决）：
        //   1) GET 线程在还款 commit 之前读到旧 DB 快照（MySQL RR 下是事务开始时的一致性快照），但写得慢；
        //   2) 还款 commit，这里的 afterCommit 删掉 Redis；
        //   3) GET 线程“迟到”地 writeRedis(旧值)，旧 read model 重新落到 Redis，直到 remoteTtl 才过期。
        // 也就是说：写后删 + 短 TTL 只能把不一致窗口收敛到 remoteTtl，并不能做到强一致。
        // 工程缓解手段（按成本递增）：把 remoteTtl 设短、delayed double-delete（commit 后删一次、延迟数百 ms 再删一次）、
        // 或给 read model 加版本号让旧值写入时被拒。本项目刻意停在“写后删 + 短 TTL + 文档化窗口”，
        // 因为账单查询能容忍秒级 stale；强一致请直接读 DB，不要靠 cache。
        // 详见 docs/cache-snapshot-design-cn.md 的 "after-commit evict 不是强一致" 一节。
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                evict(statementId);
            }
        });
    }

    private StatementReadModel loadThroughRedis(UUID id) {
        // L2 Redis 是跨实例共享 cache。先查 Redis，可以让 pod A 回源 DB 后，pod B 也复用同一份 read model。
        StatementReadModel remote = readRedis(id);
        if (remote != null) {
            return remote;
        }
        // L1/L2 都 miss 才回源 MySQL。这里调用原来的 application service，保持 source of truth 仍在 DB。
        StatementReadModel loaded = StatementReadModel.from(statementGenerationService.get(id));
        // 写回 Redis 后，本次返回值会由 Caffeine 自动放进 L1。
        writeRedis(id, loaded);
        return loaded;
    }

    private StatementReadModel readRedis(UUID id) {
        String key = redisKey(id);
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json == null) {
                // null 是正常 cache miss，不是错误；继续回源 DB。
                return null;
            }
            return objectMapper.readValue(json, StatementReadModel.class);
        } catch (JsonProcessingException exception) {
            // Redis 里是旧格式或坏 JSON 时，不要让这个坏值永久挡住请求；删除后回源 DB 重建。
            deleteCorruptRedisValue(key);
            log.warn("statement_read_cache_corrupt key={} action=evict_and_reload", key, exception);
            return null;
        } catch (RuntimeException exception) {
            // Redis 是性能层；读失败时回源 DB，不让 cache 故障变成 statement GET 故障。
            log.warn("statement_read_cache_redis_read_failed key={} fallback=db", key, exception);
            return null;
        }
    }

    private void writeRedis(UUID id, StatementReadModel value) {
        String key = redisKey(id);
        try {
            redisTemplate.opsForValue().set(
                    key,
                    objectMapper.writeValueAsString(value),
                    // Redis TTL 比 L1 长；再加 jitter，把大量 key 的过期时间错开，降低 cache avalanche。
                    remoteTtlWithJitter()
            );
        } catch (JsonProcessingException exception) {
            // 序列化失败说明这次 cache value 写不了；不要影响 GET，直接返回 DB 结果。
            log.warn("statement_read_cache_serialize_failed key={} action=skip_redis", key, exception);
        } catch (RuntimeException exception) {
            // Redis 写失败时保留本 JVM L1。本次请求成功，但其他 pod 可能仍需要回源 DB。
            log.warn("statement_read_cache_redis_write_failed key={} action=keep_l1_only", key, exception);
        }
    }

    private void evict(UUID statementId) {
        // 写路径只做 delete，不做 cache update。下一次读会用 DB 最新事实重建 read model。
        localCache.invalidate(statementId);
        String key = redisKey(statementId);
        try {
            redisTemplate.delete(key);
        } catch (RuntimeException exception) {
            // 这里会留下一个可能的短暂不一致：本 JVM L1 已删，但 Redis L2 可能还保留旧值到 remote TTL。
            // 这是 cache-aside 的常见风险，靠短 TTL、告警和必要时的重试/二次删除降低影响。
            log.warn("statement_read_cache_redis_evict_failed key={} action=l1_evicted", key, exception);
        }
    }

    private String redisKey(UUID id) {
        return REDIS_KEY_PREFIX + Objects.requireNonNull(id, "statement id must not be null");
    }

    private void deleteCorruptRedisValue(String key) {
        try {
            redisTemplate.delete(key);
        } catch (RuntimeException exception) {
            // 连坏值都删不掉时也继续回源 DB；Redis 不应该成为 statement GET 的 hard dependency。
            log.warn("statement_read_cache_corrupt_evict_failed key={} fallback=db", key, exception);
        }
    }

    private Duration remoteTtlWithJitter() {
        Duration jitter = properties.remoteTtlJitter();
        if (jitter == null || jitter.isZero() || jitter.isNegative()) {
            return properties.remoteTtl();
        }
        // jitter 是“随机多活一小段时间”，不是为了业务正确性，而是为了避免同一批 key 同秒过期。
        // 例如 batch 一次写入 1000 个 statement cache，如果都 5 分钟后过期，下一波请求会一起打 DB。
        long extraMillis = ThreadLocalRandom.current().nextLong(jitter.toMillis() + 1);
        return properties.remoteTtl().plusMillis(extraMillis);
    }
}
