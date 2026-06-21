package com.minicard.infrastructure.cache;

import java.util.function.Supplier;

/**
 * 低风险业务快照专用缓存接口。
 *
 * <p>它故意不暴露通用 Spring Cache annotation，避免把状态变更 use case 随手缓存。
 * 当前项目只缓存可从数据库重建的 snapshot，例如 statement read model 和 card snapshot；
 * 金额扣减、额度预占、幂等 claim 等写入结果仍以 MySQL transaction boundary 和 row lock 为准。</p>
 */
public interface SnapshotCache<K, V> {

    /**
     * 按 key 读取缓存；L1/L2 都 miss 时调用 loader 从 source of truth 重建。
     * loader 可以返回 null，表示不做 negative cache。
     */
    V get(K key, Supplier<V> loader);

    /**
     * 主数据发生变化后显式 evict，避免 TTL 到期前返回 stale snapshot。
     */
    void evict(K key);
}
