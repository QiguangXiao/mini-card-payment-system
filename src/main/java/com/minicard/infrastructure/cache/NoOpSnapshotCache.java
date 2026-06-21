package com.minicard.infrastructure.cache;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * 空实现 snapshot cache。
 *
 * <p>关键词：空缓存, 降级开关, snapshot cache, no-op cache,
 * feature toggle, キャッシュ無効化(キャッシュむこうか),
 * 代替実装(だいたいじっそう)。</p>
 */
final class NoOpSnapshotCache<K, V> implements SnapshotCache<K, V> {

    @Override
    public V get(K key, Supplier<V> loader) {
        Objects.requireNonNull(key, "cache key must not be null");
        return Objects.requireNonNull(loader, "cache loader must not be null").get();
    }

    @Override
    public void evict(K key) {
        Objects.requireNonNull(key, "cache key must not be null");
    }
}
