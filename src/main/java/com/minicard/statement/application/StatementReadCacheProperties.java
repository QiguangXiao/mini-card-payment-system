package com.minicard.statement.application;

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
        Duration remoteTtlJitter
) {

    public StatementReadCacheProperties {
        localTtl = localTtl == null ? Duration.ofSeconds(30) : localTtl;
        localMaximumSize = localMaximumSize == null ? 1000L : localMaximumSize;
        remoteTtl = remoteTtl == null ? Duration.ofMinutes(5) : remoteTtl;
        remoteTtlJitter = remoteTtlJitter == null ? Duration.ofSeconds(30) : remoteTtlJitter;
    }
}
