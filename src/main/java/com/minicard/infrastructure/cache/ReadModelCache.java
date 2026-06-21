package com.minicard.infrastructure.cache;

import java.util.function.Supplier;

/**
 * 低风险 read model 专用缓存接口。
 *
 * <p>它故意不暴露通用 Spring Cache annotation，避免把状态变更 use case 也随手缓存。
 * 当前项目只把可从数据库重建的查询响应放进 cache；授权、扣款、入账等写路径仍以 MySQL
 * transaction boundary 和 row lock 为准。</p>
 */
public interface ReadModelCache<K, V> {

    /**
     * 按 key 读取缓存；L1/L2 都 miss 时调用 loader 从 source of truth 重建。
     */
    V get(K key, Supplier<V> loader);

    /**
     * 主数据发生变化后显式 evict，避免 TTL 到期前返回 stale read model。
     */
    void evict(K key);
}
