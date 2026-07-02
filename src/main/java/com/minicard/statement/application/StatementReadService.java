package com.minicard.statement.application;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.minicard.repayment.application.ReceiveRepaymentCommand;
import com.minicard.repayment.application.RepaymentService;
import com.minicard.shared.domain.Money;
import com.minicard.statement.domain.Statement;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
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
 * 写的时候不直接更新 cache，而是更新 DB 后失效 cache，让下一次读重新从 DB 构造。
 * 这比“写 DB 同时更新 cache”更容易解释和排查，因为并发下更新 cache 很容易写入旧计算结果。</p>
 *
 * <p><strong>L2 用版本号 + Lua CAS + 墓碑根治"迟到写"竞态</strong>：单纯 after-commit delete 关不掉
 * "GET 在 commit 前读到旧值、evict 后才写回 L2"的迟到写（delete 把可比的版本也删了，迟到写落在空 key 上）。
 * 这里 evict 不再 delete，而是写一个带版本的<strong>墓碑</strong>(版本地板)；读回填和墓碑写都走同一段
 * Lua CAS（仅当 incoming.version >= stored.version 或不存在才写），于是旧版本的迟到写被地板挡下。
 * 版本取 statements.version，而不是 paidAmount 这种业务金额字段。详见 docs/caching-and-rate-limiting-cn.md
 * 第三部分。</p>
 *
 * <p><strong>热点 key 重建锁防缓存击穿(cache breakdown / stampede)</strong>：当一个热点 statement 的 L2
 * entry 过期，每个 pod 都会有线程同时 miss 并回源 MySQL 重建同一份 read model——Caffeine 的 single-flight
 * 只能挡住同 JVM 内的并发，挡不住跨 pod。这里在 L2 miss 后用一把跨 pod 的 Redis 重建锁
 * (`SET key token NX PX`) 做<strong>分布式 single-flight</strong>：只有抢到锁的 pod 回源 DB 并写回 L2，
 * 其余 pod 短暂自旋等待后直接复用刚写好的 L2，不重复打库。锁是 best-effort 的<strong>优化</strong>而非
 * 正确性依赖：抢锁失败/超时/Redis 故障一律 fail-open 回源（退化回原始行为，DB 仍是 source of truth）。
 * 正因为被保护的动作（只读重建 + CAS 写回）本身幂等，这里不需要 fencing token——锁偶尔不互斥最坏只是
 * 多一次回源，不会破坏数据；fencing token 只在锁保护"对共享可变状态的非幂等副作用"时才必要。</p>
 */
@Service
@Slf4j
public class StatementReadService {

    // cache name 带版本号。v3 的 <version>|<payload> 信封仍然保留，但 version 来源从 paidAmount
    // 派生值换成 statements.version；语义不兼容，所以 bump cache name，让旧 v2 key 自然过期。
    private static final String CACHE_NAME = "statement-read-model-v3";
    private static final String REDIS_KEY_PREFIX = "mini-card:cache:" + CACHE_NAME + ":";
    // 重建锁 key 与缓存 value 分开命名，避免占用同一个 key、互相覆盖。
    private static final String REBUILD_LOCK_KEY_PREFIX = "mini-card:cache:" + CACHE_NAME + ":rebuild-lock:";
    private static final String REBUILD_LOCK_METRIC = "statement.read_cache.rebuild_lock";

    // L2 value 信封格式：<version>|<payload>。
    //   - 真实值： "<version>|<read model JSON>"
    //   - 墓碑：   "<version>|"            （'|' 后为空 = tombstone，读到视作 miss）
    // 用前缀数字 + 分隔符，而不是 JSON 包一层 version，是为了让 Lua 端不依赖 cjson、只做一次数字前缀解析。
    private static final char VERSION_SEPARATOR = '|';

    // 版本化写入的 Lua CAS：仅当"Redis 里没有该 key，或已存版本不比 incoming 更新"时才写。
    // KEYS[1]=key  ARGV[1]=incoming version  ARGV[2]=信封字符串  ARGV[3]=TTL 毫秒
    // 返回 1=写入，0=被拒（已有更新版本/墓碑挡住了这次迟到写）。
    // 整段在 Redis 内单线程原子执行，所以 GET 比较 + SET 之间不会被插入其他写。
    private static final RedisScript<Long> CAS_WRITE_SCRIPT = RedisScript.of(
            "local incoming = tonumber(ARGV[1])\n"
                    + "local current = redis.call('GET', KEYS[1])\n"
                    + "if current then\n"
                    + "  local cur = tonumber(string.match(current, '^([%-%d]+)|'))\n"
                    + "  if cur ~= nil and cur > incoming then\n"
                    + "    return 0\n"
                    + "  end\n"
                    + "end\n"
                    + "redis.call('SET', KEYS[1], ARGV[2], 'PX', ARGV[3])\n"
                    + "return 1\n",
            Long.class
    );

    // 重建锁的安全释放：仅当 key 当前值 == 自己写入的 token 时才 DEL。
    // counterfactual：直接 DEL 可能误删"我超时后、别人刚抢到"的那把锁（经典误删他人锁 bug）。
    // GET + DEL 必须原子，所以放进一段 Lua；KEYS[1]=lockKey，ARGV[1]=token，返回删除数(1/0)。
    private static final RedisScript<Long> RELEASE_REBUILD_LOCK_SCRIPT = RedisScript.of(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then\n"
                    + "  return redis.call('DEL', KEYS[1])\n"
                    + "else\n"
                    + "  return 0\n"
                    + "end\n",
            Long.class
    );

    private final StatementGenerationService statementGenerationService;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final StatementReadCacheProperties properties;
    private final MeterRegistry meterRegistry;
    // 跨 pod L1 失效广播。默认 no-op；开启后走 Redis Pub/Sub，让其他 pod 也清各自 L1。
    private final StatementReadCacheBroadcaster broadcaster;
    // L1 是每个 JVM 自己的本地内存 cache，速度快但不跨 pod 共享。
    // 所以 local TTL 必须比 Redis 短，避免其他 pod 写完后，本 pod 长时间拿旧值。
    private final Cache<UUID, StatementReadModel> localCache;

    public StatementReadService(
            StatementGenerationService statementGenerationService,
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            StatementReadCacheProperties properties,
            MeterRegistry meterRegistry,
            StatementReadCacheBroadcaster broadcaster
    ) {
        this.statementGenerationService = statementGenerationService;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.broadcaster = broadcaster;
        this.localCache = Caffeine.newBuilder()
                // maximumSize 是生产 cache 的基本护栏：没有上限时，热点或恶意 key 会吃满 JVM heap。
                .maximumSize(properties.localMaximumSize())
                // expireAfterWrite 让每个 pod 的 L1 自然过期；它不是强一致失效，只是 stale window 的上限。
                .expireAfterWrite(properties.localTtl())
                .build();
    }

    /**
     * 读取 statement read model，按 L1 Caffeine -> L2 Redis -> DB rebuild 的顺序加载。
     */
    public StatementReadModel get(UUID id) {
        // 入口只问 L1。L1 miss 时，Caffeine 才调用 loadThroughRedis。
        // Cache.get(key, mappingFunction) 自带同 JVM per-key single-flight：
        // 同一个 statementId 同时 miss 时，只有一个线程进入 L2/DB loader，其他线程等结果。
        // 这只能保护本 JVM；多个 pod 同时 miss，仍可能各自回源，所以 Redis L2 和 TTL jitter 仍然需要。
        return localCache.get(id, this::loadThroughRedis);
    }

    /**
     * 在写事务成功提交后失效 statement read cache，并写版本墓碑挡住迟到旧值。
     *
     * <p>事务归属：通常由写事务中的 {@link RepaymentService#receive(ReceiveRepaymentCommand)}
     * 间接调用，但真正执行 {@link #evict(UUID, long)} 会排到 afterCommit；如果当前线程没有事务，
     * 则直接执行失效，主要服务测试或未来非事务调用。</p>
     */
    public void evictAfterCommit(Statement statement) {
        UUID statementId = statement.id();
        // 墓碑的版本 = transaction 内已经推进后的 statement.version。
        // 如果仍用 paidAmount 计算版本，将来 status/dueDate/展示字段变化但金额不变时，旧 reader 会被误判为同版本。
        long version = statement.version();
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            // 没有 Spring transaction 时直接 evict，通常用于测试或未来非事务调用。
            evict(statementId, version);
            return;
        }
        // 还款 transaction commit 后再失效缓存。afterCommit 的含义是：只有 DB 真的提交成功，cache 才需要
        // 失效；rollback 时不做多余处理。在事务内提前失效会更糟——失效后、commit 前，另一个 GET 仍读到旧
        // DB 值并写回 Redis，旧值反而被"固化"。所以失效一定排到 commit 之后。
        //
        // afterCommit 解决"什么时候失效"，但单纯 delete 关不掉"迟到写"竞态：
        //   1) GET 线程在还款 commit 之前读到旧 DB 快照（MySQL RR 下是事务开始时的一致性快照），但写得慢；
        //   2) 还款 commit，afterCommit 失效 Redis；
        //   3) GET 线程"迟到"地写回旧值——如果只是 delete，旧值会落在空 key 上、活到 remoteTtl。
        // 本实现用墓碑根治这一步：evict 写一个 version=statements.version 的墓碑作为版本地板，第 3) 步的迟到写
        // （旧版本）会被 CAS 的 "stored.version > incoming 则拒绝" 挡下。详见 evict / writeViaCas。
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                evict(statementId, version);
            }
        });
    }

    /**
     * L1 miss 后尝试读取 Redis；Redis miss 时再决定是否用跨 pod single-flight 回源 DB。
     */
    private StatementReadModel loadThroughRedis(UUID id) {
        // L2 Redis 是跨实例共享 cache。先查 Redis，可以让 pod A 回源 DB 后，pod B 也复用同一份 read model。
        StatementReadModel remote = readRedis(id);
        if (remote != null) {
            return remote;
        }
        // L1/L2 都 miss。直接回源会有缓存击穿风险：热点 key 过期瞬间每个 pod 都放线程砸向 DB。
        // 用跨 pod 重建锁做 single-flight；关闭时退回最朴素的"各自回源"。
        if (!properties.rebuildLockEnabled()) {
            return rebuildFromDb(id);
        }
        return rebuildWithSingleFlight(id);
    }

    /**
     * 回源 MySQL 重建 read model 并写回 L2。是"真正打库"的唯一入口。
     *
     * <p>保持 source of truth 仍在 DB；写回 L2 后，本次返回值会由 Caffeine 自动放进 L1。</p>
     */
    private StatementReadModel rebuildFromDb(UUID id) {
        StatementReadModel loaded = StatementReadModel.from(statementGenerationService.get(id));
        writeRedis(id, loaded);
        return loaded;
    }

    /**
     * 跨 pod single-flight：只有抢到 Redis 重建锁的请求回源 DB，其余请求等它把 L2 填好后复用。
     *
     * <p>锁是 best-effort 优化，不是正确性依赖：任何一步失败都 fail-open 回源，绝不让 GET 卡死或失败。</p>
     */
    private StatementReadModel rebuildWithSingleFlight(UUID id) {
        String lockKey = rebuildLockKey(id);
        // token 唯一，用于"只释放自己持有的那把锁"。
        String token = UUID.randomUUID().toString();
        Boolean acquired;
        try {
            // SET <lockKey> <token> NX PX <ttl>：NX 保证全局只有一个 winner；PX(TTL) 保证持锁者崩溃时
            // 锁能自动过期，不会把热点 key 永久锁死——这正是必须带 TTL、不能用裸 SETNX 的原因。
            acquired = redisTemplate.opsForValue()
                    .setIfAbsent(lockKey, token, properties.rebuildLockTtl());
        } catch (RuntimeException exception) {
            // 抢锁这步 Redis 就不可用：fail-open 直接回源，别让"击穿保护"本身变成 GET 的硬依赖。
            meterRegistry.counter(REBUILD_LOCK_METRIC, "outcome", "error").increment();
            log.warn("statement_read_cache_rebuild_lock_error key={} fallback=db", lockKey, exception);
            return rebuildFromDb(id);
        }

        if (Boolean.TRUE.equals(acquired)) {
            // winner：唯一回源者。无论成功失败都要释放锁，否则要等 TTL 才放下一个重建者进来。
            meterRegistry.counter(REBUILD_LOCK_METRIC, "outcome", "winner").increment();
            try {
                return rebuildFromDb(id);
            } finally {
                releaseRebuildLock(lockKey, token);
            }
        }

        // loser：别人正在重建。短暂自旋等 winner 把 L2 填好再复用，避免自己也打 DB。
        for (int attempt = 0; attempt < properties.rebuildLockWaitAttempts(); attempt++) {
            if (!sleepBeforeRebuildRetry()) {
                break; // 被中断：不吞中断状态，退出去走 fail-open
            }
            StatementReadModel populated = readRedis(id);
            if (populated != null) {
                meterRegistry.counter(REBUILD_LOCK_METRIC, "outcome", "contended_hit").increment();
                return populated;
            }
        }
        // winner 太慢/持锁者已死/L2 仍空：fail-open 自己回源，绝不让用户请求无限等在一把锁上。
        // 此时可能 >1 个 pod 同时回源，但这只是"退化回原始行为"，正确性不受影响。
        meterRegistry.counter(REBUILD_LOCK_METRIC, "outcome", "fallback_db").increment();
        log.debug("statement_read_cache_rebuild_wait_exhausted key={} fallback=db", lockKey);
        return rebuildFromDb(id);
    }

    /**
     * 用 Lua 只释放自己持有的 Redis rebuild lock。
     */
    private void releaseRebuildLock(String lockKey, String token) {
        try {
            redisTemplate.execute(RELEASE_REBUILD_LOCK_SCRIPT, List.of(lockKey), token);
        } catch (RuntimeException exception) {
            // 释放失败不影响正确性：锁会在 PX TTL 到点后自动消失，最多让下一个重建者多等一会儿。
            log.warn("statement_read_cache_rebuild_unlock_failed key={} action=rely_on_ttl", lockKey, exception);
        }
    }

    /** @return true 正常睡完；false 被中断（已恢复中断标志，调用方应停止自旋）。 */
    private boolean sleepBeforeRebuildRetry() {
        try {
            Thread.sleep(properties.rebuildLockWaitInterval().toMillis());
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 生成单个 statement 的 Redis rebuild lock key。
     */
    private String rebuildLockKey(UUID id) {
        return REBUILD_LOCK_KEY_PREFIX + Objects.requireNonNull(id, "statement id must not be null");
    }

    /**
     * 从 Redis 读取版本化 read model；miss、墓碑、坏值和 Redis 故障都返回 null。
     */
    private StatementReadModel readRedis(UUID id) {
        String key = redisKey(id);
        try {
            String stored = redisTemplate.opsForValue().get(key);
            if (stored == null) {
                // null 是正常 cache miss，不是错误；继续回源 DB。
                return null;
            }
            int separator = stored.indexOf(VERSION_SEPARATOR);
            if (separator < 0) {
                // 没有 "<version>|" 前缀 = 非本版本格式（理论上 v2 不会遇到 v1 值，仍防御）：删掉回源重建。
                deleteCorruptRedisValue(key);
                log.warn("statement_read_cache_unversioned key={} action=evict_and_reload", key);
                return null;
            }
            String payload = stored.substring(separator + 1);
            if (payload.isEmpty()) {
                // 墓碑（版本地板占位，'|' 后为空）：视作 miss，回源 DB 重建真值。
                // 回填时 CAS 会用 incoming.version 和墓碑版本比较：新鲜读替换墓碑，迟到写被拒。
                return null;
            }
            return objectMapper.readValue(payload, StatementReadModel.class);
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

    /**
     * 将 DB rebuild 出来的 read model 通过版本化 CAS 写回 Redis。
     */
    private void writeRedis(UUID id, StatementReadModel value) {
        String key = redisKey(id);
        try {
            long version = value.version();
            // 信封 = "<version>|<read model JSON>"。版本同时单独作为 ARGV[1] 传给 CAS，省得脚本再解析一次。
            String envelope = version + "|" + objectMapper.writeValueAsString(value);
            Long written = writeViaCas(key, version, envelope, remoteTtlWithJitter().toMillis());
            if (written != null && written == 0L) {
                // CAS 拒绝：Redis 里已有更新版本或墓碑，说明本次读到的是较旧快照。
                // 不写回缓存即可（避免迟到写覆盖新值）；本次仍把 loaded 返回给调用方，那是它读到的一致结果。
                log.debug("statement_read_cache_cas_skipped key={} version={}", key, version);
            }
        } catch (JsonProcessingException exception) {
            // 序列化失败说明这次 cache value 写不了；不要影响 GET，直接返回 DB 结果。
            log.warn("statement_read_cache_serialize_failed key={} action=skip_redis", key, exception);
        } catch (RuntimeException exception) {
            // Redis 写失败时保留本 JVM L1。本次请求成功，但其他 pod 可能仍需要回源 DB。
            log.warn("statement_read_cache_redis_write_failed key={} action=keep_l1_only", key, exception);
        }
    }

    /**
     * 版本化 CAS 写：把信封写进 L2，但仅当"key 不存在或已存版本不比 incoming 更新"才写。
     * 读回填和墓碑写共用它——区别只在 envelope 是"真实值"还是"版本地板"。
     */
    private Long writeViaCas(String key, long version, String envelope, long ttlMillis) {
        return redisTemplate.execute(
                CAS_WRITE_SCRIPT,
                List.of(key),
                Long.toString(version),
                envelope,
                Long.toString(ttlMillis)
        );
    }

    /**
     * 只失效本 JVM 的 L1，不碰 L2、不再广播。
     *
     * <p>这是 Redis Pub/Sub 订阅者收到"其他 pod 失效了某 statement"时的回调入口
     * （见 StatementReadCacheEvictListener）。</p>
     *
     * <p><strong>关键约束（避免广播风暴/无限循环）</strong>：订阅者只能调用本方法（L1-only），
     * 绝不能调用 {@link #evict}——那会再删一次 L2 并再次 {@code broadcastEvict}，
     * 于是 N 个 pod 会就同一条失效互相广播，形成风暴。失效广播必须是单向的：
     * 写侧 evict 负责"删 L2 + 广播"，接收侧只负责"清自己的 L1"。</p>
     */
    public void invalidateLocal(UUID statementId) {
        localCache.invalidate(statementId);
    }

    /**
     * 执行实际 cache 失效：清本地 L1、写 Redis 墓碑、广播跨 pod L1 失效。
     *
     * <p>事务归属：应在写事务 commit 之后调用，通常由 {@link #evictAfterCommit(Statement)}
     * 的 afterCommit 回调触发；不要在业务写入尚未提交时直接调用。</p>
     */
    private void evict(UUID statementId, long version) {
        // 写路径不更新 cache，而是写一个"版本地板"墓碑让下一次读回源重建。三步顺序：
        // 先清本 pod L1 → 在 L2 写墓碑(版本地板) → 广播让其他 pod 清各自 L1。
        localCache.invalidate(statementId);
        String key = redisKey(statementId);
        try {
            // 墓碑信封 = "<version>|"（'|' 后为空）。同样走 CAS：如果 L2 里已有更高版本（更晚的还款），
            // 这次较低版本的墓碑会被拒绝，不会把地板降低。墓碑 TTL 远短于 remoteTtl，只需活到首个新鲜读替换它。
            writeViaCas(key, version, version + "|", properties.tombstoneTtl().toMillis());
        } catch (RuntimeException exception) {
            // 墓碑写失败：退化成"只清了本 pod L1"。此时迟到写可能落在空 key 上（回到旧 delete 的风险），
            // 靠 remoteTtl 和告警兜底。Redis 不应成为 statement GET / 还款的 hard dependency。
            log.warn("statement_read_cache_tombstone_failed key={} action=l1_evicted_only", key, exception);
        }
        // 跨 pod L1 失效：上面只清了"本 pod"的 L1，其他 pod 的 Caffeine 还留着旧值最长 local-ttl。
        // 广播一条 Pub/Sub 消息，让所有 pod（含自己）的订阅者 invalidateLocal，把跨 pod L1 stale 窗口
        // 从 local-ttl 压到一次广播延迟（亚毫秒~毫秒级）。它是 best-effort：广播丢失时退回 local-ttl 兜底。
        // 默认是 no-op 实现；只有显式开启 statement.read-cache.broadcast.enabled=true 才走 Redis Pub/Sub。
        broadcaster.broadcastEvict(statementId);
    }

    /**
     * 生成 statement read model 的 Redis value key。
     */
    private String redisKey(UUID id) {
        return REDIS_KEY_PREFIX + Objects.requireNonNull(id, "statement id must not be null");
    }

    /**
     * 尝试删除 Redis 中无法解析的旧值或坏值，随后让调用方回源 DB。
     */
    private void deleteCorruptRedisValue(String key) {
        try {
            redisTemplate.delete(key);
        } catch (RuntimeException exception) {
            // 连坏值都删不掉时也继续回源 DB；Redis 不应该成为 statement GET 的 hard dependency。
            log.warn("statement_read_cache_corrupt_evict_failed key={} fallback=db", key, exception);
        }
    }

    /**
     * 计算带随机抖动的 Redis TTL，降低批量 key 同时过期造成的 DB 峰值。
     */
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
