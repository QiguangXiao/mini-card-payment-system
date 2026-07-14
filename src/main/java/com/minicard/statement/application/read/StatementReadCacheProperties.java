package com.minicard.statement.application.read;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Statement GET 两级缓存配置。
 *
 * <p>关键词：账单查询缓存, Caffeine L1, Redis L2, cache-aside,
 * statement read cache, after-commit eviction, 請求照会(せいきゅうしょうかい),
 * キャッシュ(キャッシュ)。</p>
 *
 * <p>这几个配置对应生产 cache 最常见的四个问题：
 * L1 能放多久、L1 最多放多少、Redis L2 能放多久、Redis key 是否会同一时间雪崩过期。
 * 它们放在 typed properties 里，而不是散在 `@Value` 字符串中，方便测试和 review。</p>
 */
@ConfigurationProperties(prefix = "statement.read-cache")
public record StatementReadCacheProperties(
        /*
         * 本 JVM 内 Caffeine 的 TTL。它越长，本机命中越高；但多 pod 下也越容易读到其他 pod
         * 已经更新后的旧值，所以生产上 L1 TTL 通常短于 L2。
         */
        Duration localTtl,
        /*
         * 本 JVM 内最多缓存多少个 statement read model。生产 cache 必须有容量上限，
         * 否则大量不同 key 会把 heap 变成隐形数据库。
         */
        Long localMaximumSize,
        /*
         * Redis L2 的基础 TTL。L2 跨 pod 共享，减少所有实例一起回源 MySQL 的次数。
         */
        Duration remoteTtl,
        /*
         * Redis TTL 抖动上限。实际 TTL = remoteTtl + [0, remoteTtlJitter] 随机值，
         * 用来错开过期时间，降低 cache avalanche。
         */
        Duration remoteTtlJitter,
        /*
         * 还款 after-commit 写入 L2 的"版本地板"(tombstone)的存活时间。evict 不再 delete，而是写一个带版本
         * 的墓碑作为地板，挡住迟到写覆盖新值（见 StatementReadService 的 CAS 写）。
         * 必须 >= 一个慢 GET 从"读 DB"到"写回 L2"的最大时滞，否则地板先过期、迟到写又能落在空 key 上。
         * 它只需活到第一个新鲜 reader 用真实值替换掉地板为止，所以通常远短于 remoteTtl。
         */
        Duration tombstoneTtl,
        /*
         * 是否启用"热点 key 重建锁"防缓存击穿(cache breakdown / stampede)。
         * 关闭时退回最朴素行为：每个 pod 在 L2 miss 时各自回源 DB 重建。
         */
        Boolean rebuildLockEnabled,
        /*
         * 重建锁(Redis SET NX PX)的持有时间。它必须 >= "回源 DB + 写回 L2"的正常耗时，否则锁会在 winner
         * 还没写完 L2 时就过期，放第二个重建者进来，single-flight 形同虚设。
         * 同时它又是持锁者崩溃后的自动释放上限：取一个"足够覆盖一次慢重建、又不会把热点 key 锁太久"的值。
         */
        Duration rebuildLockTtl,
        /*
         * loser(没抢到锁的请求)等待 winner 把 L2 填好的最大自旋次数。次数 × 间隔 = 用户请求最多被这把锁
         * 阻塞多久；超过后 fail-open 自己回源，绝不让请求无限等在锁上。
         */
        Integer rebuildLockWaitAttempts,
        /*
         * loser 每次自旋之间的等待间隔。太小=空转浪费 CPU 且狂查 Redis；太大=命中 L2 前的额外延迟变高。
         */
        Duration rebuildLockWaitInterval
) {

    public StatementReadCacheProperties {
        localTtl = localTtl == null ? Duration.ofSeconds(30) : localTtl;
        localMaximumSize = localMaximumSize == null ? 1000L : localMaximumSize;
        remoteTtl = remoteTtl == null ? Duration.ofMinutes(5) : remoteTtl;
        remoteTtlJitter = remoteTtlJitter == null ? Duration.ofSeconds(30) : remoteTtlJitter;
        tombstoneTtl = tombstoneTtl == null ? Duration.ofSeconds(10) : tombstoneTtl;
        rebuildLockEnabled = rebuildLockEnabled == null ? Boolean.TRUE : rebuildLockEnabled;
        rebuildLockTtl = rebuildLockTtl == null ? Duration.ofSeconds(2) : rebuildLockTtl;
        rebuildLockWaitAttempts = rebuildLockWaitAttempts == null ? 5 : rebuildLockWaitAttempts;
        rebuildLockWaitInterval = rebuildLockWaitInterval == null
                ? Duration.ofMillis(20) : rebuildLockWaitInterval;
    }
}
